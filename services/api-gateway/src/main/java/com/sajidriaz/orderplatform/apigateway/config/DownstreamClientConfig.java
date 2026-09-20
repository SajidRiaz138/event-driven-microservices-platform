package com.sajidriaz.orderplatform.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * The HTTP client the gateway forwards with.
 *
 * <p>Timeouts are set explicitly, which is the entire point of this class existing: a proxy whose
 * client waits indefinitely converts one slow downstream into exhausted request threads at the
 * edge, and from a caller's side the whole platform stops responding rather than one route
 * failing. Bounded waits turn that into a 504 for the affected route.
 *
 * <p>{@link JdkClientHttpRequestFactory} over the JDK {@link HttpClient}: it supports every HTTP
 * method a downstream might expose (the older {@code HttpURLConnection}-based factory cannot
 * send {@code PATCH}) and needs no third-party client on the classpath.
 *
 * <p>Redirects are not followed. A gateway that follows them would resolve a downstream redirect
 * itself and hand the client a response from a URL it never asked for; the redirect belongs to
 * the client, so it is passed through as the 3xx it is.
 */
@Configuration
public class DownstreamClientConfig
{

    @Bean
    public RestClient downstreamRestClient(GatewayProperties properties)
    {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
