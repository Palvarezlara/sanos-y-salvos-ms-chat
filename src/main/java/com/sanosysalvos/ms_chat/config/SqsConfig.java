package com.sanosysalvos.ms_chat.config;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Cliente SQS para consumir "mascota.eliminada" y borrar las conversaciones de ese reporte. */
@Configuration
public class SqsConfig {

    @Bean(destroyMethod = "close")
    SqsClient sqsClient(
            @Value("${aws.region:us-east-1}") String region,
            @Value("${aws.endpoint-override:}") String endpointOverride) {
        var builder = SqsClient.builder().region(Region.of(region));
        if (!endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }
}
