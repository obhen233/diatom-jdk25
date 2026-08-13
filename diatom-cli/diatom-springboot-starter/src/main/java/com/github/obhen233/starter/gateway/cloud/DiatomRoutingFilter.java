package com.github.obhen233.starter.gateway.cloud;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Spring Cloud Gateway 全局过滤器
 * 将匹配 /gateway/v1/** 的请求路由到可用的 Worker 节点
 *
 * 启用条件:
 * 1. classpath 中存在 spring-cloud-starter-gateway
 * 2. diatom.gateway.cloud.routing-enabled=true
 *
 * 路由策略:
 * - /gateway/v1/chat → 转发到负载最低的 Worker
 * - /gateway/v1/tasks/{taskId} → 转发到任务分配的 Worker
 * - 其他请求 → 由 Gateway 本地处理
 */
public class DiatomRoutingFilter implements GlobalFilter, Ordered {
    private static final Logger logger = LoggerFactory.getLogger(DiatomRoutingFilter.class);

    private final WorkerRegistry registry;
    private final int order;

    public DiatomRoutingFilter(WorkerRegistry registry, int order) {
        this.registry = registry;
        this.order = order;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 只处理 Gateway API 路径
        if (!path.startsWith("/gateway/v1/")) {
            return chain.filter(exchange);
        }

        // Chat 请求路由到 Worker
        if (path.contains("/chat")) {
            List<WorkerInfo> workers = registry.availableWorkers();
            if (!workers.isEmpty()) {
                WorkerInfo target = workers.getFirst();
                String targetUrl = "http://" + target.getHost() + ":" + target.getPort();
                logger.debug("Routing {} to worker {} at {}", path, target.getWorkerId(), targetUrl);

                // 修改请求 URI 指向 Worker
                exchange = exchange.mutate()
                        .request(exchange.getRequest().mutate()
                                .uri(java.net.URI.create(targetUrl + path))
                                .build())
                        .build();
            }
        }

        return chain.filter(exchange);
    }
}
