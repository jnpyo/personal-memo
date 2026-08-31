package local.personalmemo.calendar.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CalendarFeedPublicationProperties.class)
public class CalendarFeedPublicationConfiguration {}
