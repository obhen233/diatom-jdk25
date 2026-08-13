package com.github.obhen233.jdtls;

import com.github.obhen233.compiler.repository.IdeSettingRepository;
import org.eclipse.lsp4j.services.LanguageServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JdtLsConfig {

    @Autowired
    private IdeSettingRepository settingRepo;

    @Bean
    public SimpleTextDocumentService textDocumentService() {
        return new SimpleTextDocumentService(settingRepo);
    }

    @Bean
    public LanguageServer languageServer(SimpleTextDocumentService textDocumentService) {
        return new SimpleLanguageServer(textDocumentService, new SimpleWorkspaceService());
    }

    @Bean
    public JdtLsSocketHandler jdtLsSocketHandler(LanguageServer languageServer) {
        return new JdtLsSocketHandler(languageServer);
    }
}
