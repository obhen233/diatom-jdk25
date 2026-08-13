package com.github.obhen233.compiler.auth;

import com.github.obhen233.compiler.i18n.I18n;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import com.github.obhen233.compiler.auth.NotDockerCondition;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 默认认证提供者：使用操作系统当前运行用户的账号密码验证。
 * - Windows: 通过 "net use" 命令验证本地用户凭据
 * - Linux/Mac: 通过 PAM (su -c) 验证
 * 
 * 特殊规则:
 * - Windows Administrator 无密码时自动放行
 * - Linux/Mac 禁止 root 登录
 */
@Component
@Conditional(NotDockerCondition.class)
@ConditionalOnMissingBean(value = AuthProvider.class, ignored = OsAuthProvider.class)
public class OsAuthProvider implements AuthProvider {

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");
    private static final String CURRENT_USER = System.getProperty("user.name");

    @Override
    public AuthResult authenticate(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            return AuthResult.fail(I18n.get("auth.os.usernameEmpty"));
        }
        username = username.trim();

        // Linux/Mac 禁止 root 登录
        if (!IS_WINDOWS && "root".equals(username)) {
            return AuthResult.fail(I18n.get("auth.os.rootForbidden"));
        }

        // 只允许用当前运行 IDE 的系统用户登录
        if (!username.equals(CURRENT_USER)) {
            return AuthResult.fail(I18n.get("auth.os.currentUserOnly", CURRENT_USER));
        }

        if (password == null) password = "";

        try {
            if (IS_WINDOWS) {
                return verifyWindows(username, password);
            } else {
                return verifyUnix(username, password);
            }
        } catch (Exception e) {
            return AuthResult.fail(I18n.get("auth.os.authException", e.getMessage()));
        }
    }

    @Override
    public String getAutoLoginUser() {
        if (IS_WINDOWS) {
            // Windows: 如果是 Administrator 且没有密码，自动放行
            if ("Administrator".equalsIgnoreCase(CURRENT_USER)) {
                try {
                    // 尝试用空密码验证
                    AuthResult result = verifyWindows(CURRENT_USER, "");
                    if (result.isSuccess()) {
                        return CURRENT_USER;
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    @Override
    public String getProviderName() {
        return "os-auth";
    }

    /**
     * Windows 验证：使用 PowerShell 调用 Win32 API 验证本地用户凭据。
     * 安全措施：通过 stdin 传入密码，避免命令行注入风险。
     */
    private AuthResult verifyWindows(String username, String password) throws Exception {
        if (password.isEmpty()) {
            // 直接信任，跳过验证
            return AuthResult.success(username);
        }

        // 验证用户名仅包含安全字符（字母、数字、下划线、点、连字符）
        if (!username.matches("[a-zA-Z0-9_.\\-]+")) {
            return AuthResult.fail(I18n.get("auth.os.wrongPassword"));
        }

        // PowerShell 中单引号需要转义为 ''，防止字符串拼接问题
        String escapedUsername = username.replace("'", "''");

        // 通过 stdin 传入密码，避免命令注入风险
        String psScript =
            "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; " +
            "$pwd = [Console]::In.ReadLine(); " +
            "Add-Type -AssemblyName System.DirectoryServices.AccountManagement; " +
            "$ctx = New-Object System.DirectoryServices.AccountManagement.PrincipalContext(" +
            "[System.DirectoryServices.AccountManagement.ContextType]::Machine); " +
            "if ($ctx.ValidateCredentials('" + escapedUsername + "', $pwd)) { Write-Output 'OK' } " +
            "else { Write-Output 'FAIL' }";

        ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psScript);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        // 通过 stdin 安全传入密码
        p.getOutputStream().write((password + "\n").getBytes("UTF-8"));
        p.getOutputStream().flush();
        p.getOutputStream().close();

        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) output.append(line.trim());

        boolean finished = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            return AuthResult.fail(I18n.get("auth.os.timeout"));
        }

        if (output.toString().contains("OK")) {
            return AuthResult.success(username);
        }
        return AuthResult.fail(I18n.get("auth.os.wrongPassword"));
    }

    /**
     * Linux/Mac 验证：使用 su 命令验证用户密码。
     */
    private AuthResult verifyUnix(String username, String password) throws Exception {
        // 使用 su -c 'echo OK' username，通过 stdin 传入密码
        // 注意：这需要当前进程有权限执行 su
        ProcessBuilder pb;
        if (IS_MAC) {
            // macOS: 使用 dscl 验证
            pb = new ProcessBuilder("/usr/bin/dscl", "/Local/Default", "-authonly", username, password);
        } else {
            // Linux: 使用 su 验证
            pb = new ProcessBuilder("su", "-c", "echo OK", username);
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();

        if (!IS_MAC) {
            // Linux su 需要通过 stdin 传入密码
            p.getOutputStream().write((password + "\n").getBytes());
            p.getOutputStream().flush();
            p.getOutputStream().close();
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) output.append(line);
        boolean finished = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            return AuthResult.fail(I18n.get("auth.os.timeout"));
        }

        int exitCode = p.exitValue();
        if (exitCode == 0) {
            return AuthResult.success(username);
        }
        return AuthResult.fail(I18n.get("auth.os.wrongPassword"));
    }

    private boolean isPasswordEmpty(String username) {
        try {
            String psScript =
                    "Add-Type -TypeDefinition @\"\n" +
                            "using System;\n" +
                            "using System.Runtime.InteropServices;\n" +
                            "public class WinAuth {\n" +
                            "    [DllImport(\"advapi32.dll\", SetLastError = true)]\n" +
                            "    public static extern bool LogonUser(\n" +
                            "        string lpszUsername,\n" +
                            "        string lpszDomain,\n" +
                            "        string lpszPassword,\n" +
                            "        int dwLogonType,\n" +
                            "        int dwLogonProvider,\n" +
                            "        out IntPtr phToken);\n" +
                            "    [DllImport(\"kernel32.dll\")]\n" +
                            "    public static extern int GetLastError();\n" +
                            "}\n" +
                            "\"@\n" +
                            "$token = [IntPtr]::Zero\n" +
                            "$result = [WinAuth]::LogonUser('" + username + "', '.', '', 2, 0, [ref] $token)\n" +
                            "if ($result) { \n" +
                            "    Write-Output 'EMPTY'\n" +
                            "    [WinAuth]::CloseHandle($token)\n" +
                            "} else { \n" +
                            "    $err = [WinAuth]::GetLastError()\n" +
                            "    if ($err -eq 1327) { Write-Output 'EMPTY' }\n" +
                            "    else { Write-Output 'NOT_EMPTY' }\n" +
                            "}";

            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; " +psScript);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) output.append(line.trim());
            p.waitFor();

            return "EMPTY".equals(output.toString().trim());
        } catch (Exception e) {
            return false;
        }
    }
}
