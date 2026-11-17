package org.studieojavry.iamapi.agentconnection.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** iam-api 는 @ConfigurationPropertiesScan 이 없어 명시 등록이 필요하다. */
@Configuration
@EnableConfigurationProperties(AgentConnectorProperties::class)
class AgentConnectorConfig
