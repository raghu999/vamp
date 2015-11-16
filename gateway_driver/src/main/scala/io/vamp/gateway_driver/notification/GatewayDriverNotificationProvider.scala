package io.vamp.gateway_driver.notification

import io.vamp.common.notification.{ DefaultPackageMessageResolverProvider, LoggingNotificationProvider }

trait GatewayDriverNotificationProvider extends LoggingNotificationProvider with DefaultPackageMessageResolverProvider