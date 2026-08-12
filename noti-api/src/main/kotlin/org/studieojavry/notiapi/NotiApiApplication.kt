package org.studieojavry.notiapi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class NotiApiApplication

fun main(args: Array<String>) {
  runApplication<NotiApiApplication>(*args)
}
