package com.sanosysalvos.ms_chat.config;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;

/** Cliente SNS para publicar el aviso de "mensaje nuevo" (lo envía por correo ms-alertas). */
@Configuration
public class SnsConfig {

    @Bean(destroyMethod = "close")
    SnsClient snsClient(
            @Value("${aws.region:us-east-1}") String region,
            @Value("${aws.endpoint-override:}") String endpointOverride) {
        // sin timeouts un SNS lento dejaba colgado al hilo que publica el aviso
        var builder = SnsClient.builder().region(Region.of(region))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(Duration.ofSeconds(2))
                        .apiCallTimeout(Duration.ofSeconds(5))
                        .build());

        // Solo en Docker local: apunta a LocalStack en vez de AWS real, así no hace falta
        // sesión del lab para probar. En AWS se usan las credenciales del rol de la tarea.
        if (!endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }
}
