package io.vamp.container_driver.kubernetes

import io.vamp.common.{ Config, Namespace }

import scala.concurrent.duration.FiniteDuration

object K8sConfig {

  import KubernetesContainerDriver._

  def apply()(implicit namespace: Namespace): K8sConfig = {
    K8sConfig(
      namespace = namespace.name,
      url = Config.string(s"$config.url")(),
      bearer = Config.string(s"$config.bearer")(),
      token = Config.string(s"$config.token")(),
      username = Config.string(s"$config.username")(),
      password = Config.string(s"$config.password")(),
      serverCaCert = Config.string(s"$config.server-ca-cert")(),
      tlsCheck = Config.boolean(s"$config.tls-check")(),
      cache = K8sCacheConfig(
        readTimeToLivePeriod = Config.duration(s"$config.cache.read-time-to-live")(),
        writeTimeToLivePeriod = Config.duration(s"$config.cache.write-time-to-live")(),
        failureTimeToLivePeriod = Config.duration(s"$config.cache.failure-time-to-live")()
      )
    )
  }
}

case class K8sConfig(
  namespace:    String,
  url:          String,
  bearer:       String,
  token:        String,
  username:     String,
  password:     String,
  serverCaCert: String,
  tlsCheck:     Boolean,
  cache:        K8sCacheConfig
)

case class K8sCacheConfig(
  readTimeToLivePeriod:    FiniteDuration,
  writeTimeToLivePeriod:   FiniteDuration,
  failureTimeToLivePeriod: FiniteDuration
)
