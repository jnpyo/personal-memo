package local.personalmemo.analysis.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CloudGatewayExecutionProperties.class)
public class CloudGatewayExecutionConfiguration {}
