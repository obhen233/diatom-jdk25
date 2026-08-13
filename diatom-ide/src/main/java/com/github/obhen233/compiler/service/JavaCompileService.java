package com.github.obhen233.compiler.service;

import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.dto.CompileResult;

/**
 * JAVA编译器service接口
 * 支持按项目编译（ECJ），自动收集源文件和依赖
 */
public interface JavaCompileService {

    /**
     * 按项目编译所有 Java 源文件
     *
     * @param projectName 项目名称
     * @param jdkVersion  目标 JDK 版本号 (5-25)
     * @param mainClass   要运行的主类全限定名（如 "Main" 或 "com.example.Main"）
     * @return 编译后的主类 Class
     */
    Class compile(String projectName, int jdkVersion, String mainClass) throws Exception;

    ApiResponse<CompileResult> executeMainMethod(Class clazz) throws Exception;

    ApiResponse<CompileResult> executeMainMethod(Class clazz, String[] args) throws Exception;

    ApiResponse<CompileResult> executeMainMethod(Class clazz, Long timeLimit) throws Exception;

    ApiResponse<CompileResult> executeMainMethod(Class clazz, Long timeLimit, String[] args) throws Exception;
}
