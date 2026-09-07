package org.studieojavry.publishapi.shared.config

import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationPropertiesScan(basePackages = ["org.studieojavry.publishapi"])
class ConfigRegistration
