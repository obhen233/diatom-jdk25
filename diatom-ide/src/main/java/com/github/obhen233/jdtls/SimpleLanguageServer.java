package com.github.obhen233.jdtls;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.*;

import java.util.concurrent.CompletableFuture;

/**
 * 轻量级 LanguageServer 实现，接入 JDT Core 提供 Java 语言服务。
 */
public class SimpleLanguageServer implements LanguageServer, LanguageClientAware {

    private LanguageClient client;
    private final SimpleTextDocumentService textDocumentService;
    private final SimpleWorkspaceService workspaceService;

    public SimpleLanguageServer(SimpleTextDocumentService textDocumentService, SimpleWorkspaceService workspaceService) {
        this.textDocumentService = textDocumentService;
        this.workspaceService = workspaceService;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        capabilities.setCompletionProvider(new CompletionOptions(false, java.util.Arrays.asList(".", " ")));
        // lsp4j 0.14.0+ requires Either<Boolean, CodeActionOptions> instead of boolean
        capabilities.setCodeActionProvider(Either.forLeft(true));
        capabilities.setReferencesProvider(Either.forLeft(true));
        capabilities.setDefinitionProvider(Either.forLeft(true));
        capabilities.setImplementationProvider(Either.forLeft(true));

        InitializeResult result = new InitializeResult(capabilities);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        // no-op
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
        // 将客户端引用传递给 TextDocumentService，用于推送诊断
        textDocumentService.setClient(client);
    }
}
