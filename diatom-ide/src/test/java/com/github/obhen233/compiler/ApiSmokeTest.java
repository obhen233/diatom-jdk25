package com.github.obhen233.compiler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 冒烟测试：对真实启动的 Tomcat 实例验证认证过滤器放行健康检查、
 * 拦截受保护端点，并验证登录后 token 可被校验。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-ide.db",
        "spring.jpa.hibernate.ddl-auto=update"
})
class ApiSmokeTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void healthEndpointIsOpen() {
        ResponseEntity<Map> resp = rest.getForEntity("/actuator/health", Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("UP", resp.getBody().get("status"));
    }

    @Test
    void protectedEndpointRejectsWithoutToken() {
        // /compile 在认证保护下，无 token 时 AuthFilter 应返回 401
        ResponseEntity<Map> resp = rest.getForEntity("/compile", Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(false, resp.getBody().get("success"));
    }

    @Test
    void loginThenValidateToken() {
        // Windows 下 OsAuthProvider 对空密码直接放行当前用户；
        // 该行为与真实运行环境一致（app 在本机以当前用户运行）。
        String username = System.getProperty("user.name");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(
                "{\"username\":\"" + username + "\",\"password\":\"\"}", headers);

        ResponseEntity<Map> loginResp = rest.exchange("/auth/login", HttpMethod.POST, request, Map.class);
        assertEquals(HttpStatus.OK, loginResp.getStatusCode());
        assertNotNull(loginResp.getBody());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) loginResp.getBody().get("data");
        assertNotNull(data, "login response should contain data");
        assertEquals(true, data.get("success"));
        String token = (String) data.get("token");
        assertNotNull(token, "login should return a token");

        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.set("X-Auth-Token", token);
        HttpEntity<Void> authRequest = new HttpEntity<>(authHeaders);

        ResponseEntity<Map> validateResp = rest.exchange("/auth/validate", HttpMethod.GET, authRequest, Map.class);
        assertEquals(HttpStatus.OK, validateResp.getStatusCode());
        assertNotNull(validateResp.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> vdata = (Map<String, Object>) validateResp.getBody().get("data");
        assertNotNull(vdata);
        assertEquals(true, vdata.get("valid"));
        assertTrue(validateResp.getStatusCode().is2xxSuccessful());
    }

    @Test
    void loginRejectsBlankUsernameViaValidation() {
        // @Valid + @NotBlank 应拦截空用户名，返回 ApiResponse.fail
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{\"username\":\"\",\"password\":\"x\"}", headers);

        ResponseEntity<Map> resp = rest.exchange("/auth/login", HttpMethod.POST, request, Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(false, resp.getBody().get("success"));
        String message = (String) resp.getBody().get("message");
        assertNotNull(message);
        assertTrue(message.contains("用户名不能为空"), "validation message should surface, got: " + message);
    }

    @Test
    void malformedJsonBodyReturnsFail() {
        // HttpMessageNotReadableException 应转换为 ApiResponse.fail
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("not-json", headers);

        ResponseEntity<Map> resp = rest.exchange("/auth/login", HttpMethod.POST, request, Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(false, resp.getBody().get("success"));
    }
}
