package com.github.obhen233.compiler.service.workspace;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.i18n.I18n;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Map;
import java.util.HashMap;

/**
 * Package service - handles Java package creation
 */
@Service
public class PackageService {

    @Autowired
    private FileOperationService fileOperationService;

    /**
     * Create a new Java package (create package path under src)
     */
    public Map<String, Object> createPackage(String name, String parentPath, String packageName) {
        String effectiveParentPath = parentPath != null && !parentPath.isEmpty() ? parentPath : "src";
        if (packageName == null || packageName.trim().isEmpty()) return fail(I18n.get("file.packageNameInvalid"));
        // Validate package name format
        if (!packageName.matches("[a-zA-Z_$][a-zA-Z0-9_$]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*")) {
            return fail(I18n.get("file.packageNameInvalid"));
        }
        File parent = fileOperationService.resolveProjectFile(name, effectiveParentPath);
        if (parent == null || !parent.isDirectory()) return fail(I18n.get("file.parentNotExist"));
        String pkgPath = packageName.replace('.', File.separatorChar);
        File pkgDir = new File(parent, pkgPath);
        if (pkgDir.exists()) return fail(I18n.get("file.packageAlreadyExists"));
        return pkgDir.mkdirs() ? ok() : fail(I18n.get("file.packageCreateFail"));
    }

    Map<String, Object> ok() {
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        return r;
    }

    Map<String, Object> fail(String msg) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        r.put("message", msg);
        return r;
    }
}
