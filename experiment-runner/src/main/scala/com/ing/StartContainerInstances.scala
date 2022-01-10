package com.ing

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

import com.ing.rebel.experiment.aws.AwsLib
import software.amazon.awssdk.services.ec2.model._

import scala.compat.java8.FutureConverters._
import scala.concurrent.Await
import scala.concurrent.duration.Duration

object StartContainerInstances {

  val userData: String =
    """#!/bin/bash
      |cat /etc/ecs/ecs.config | grep -v 'ECS_ENGINE_TASK_CLEANUP_WAIT_DURATION' > /tmp/ecs.config
      |echo "ECS_ENGINE_TASK_CLEANUP_WAIT_DURATION=1m" >> /tmp/ecs.config
      |mv -f /tmp/ecs.config /etc/ecs/
      |
      |sed -i.bak 's/^OPTIONS=.*/OPTIONS="--default-ulimit nofile=262144:262144"/' /etc/sysconfig/docker
      |
      |echo "*   hard  nofile  262144" | tee --append /etc/security/limits.conf
      |echo "*   soft  nofile  262144" | tee --append /etc/security/limits.conf
      |
      |# more ports for testing
      |sudo sysctl -w net.ipv4.ip_local_port_range="1025 65535"
      |sudo sysctl -w net.core.somaxconn=1024
      |
      |# increase the maximum number of possible open file descriptors:
      |echo 300000 | sudo tee /proc/sys/fs/nr_open
      |echo 300000 | sudo tee /proc/sys/fs/file-max
      |
      |echo "net.ipv4.tcp_max_syn_backlog = 40000
      |net.core.somaxconn = 40000
      |net.core.wmem_default = 8388608
      |net.core.rmem_default = 8388608
      |net.ipv4.tcp_sack = 1
      |net.ipv4.tcp_window_scaling = 1
      |net.ipv4.tcp_fin_timeout = 15
      |net.ipv4.tcp_keepalive_intvl = 30
      |net.ipv4.tcp_tw_reuse = 1
      |net.ipv4.tcp_moderate_rcvbuf = 1
      |net.core.rmem_max = 134217728
      |net.core.wmem_max = 134217728
      |net.ipv4.tcp_mem  = 134217728 134217728 134217728
      |net.ipv4.tcp_rmem = 4096 277750 134217728
      |net.ipv4.tcp_wmem = 4096 277750 134217728
      |net.core.netdev_max_backlog = 300000" >> /etc/sysctl.conf
      |
      |
      |stop ecs
      |service docker stop
      |service docker start
      |start ecs
    """.stripMargin

  def main(args: Array[String]): Unit = {
    val targetCapacity = if (args.nonEmpty) args(0).toInt else 1

    val ec2 = AwsLib.ec2

    // Tryout
    implicit def funToConsumer[A, B](fun: A => B) = new Consumer[A] {
      override def accept(t: A): Unit = fun(t)
    }

    // TODO possible use backoff here too: AwsLib#retryBackoffAwsSdk
    val prog: CompletableFuture[RequestSpotFleetResponse] = ec2.requestSpotFleet(
      f => f
        .spotFleetRequestConfig(
          _.iamFleetRole("arn:aws:iam::270458169468:role/aws-ec2-spot-fleet-role")
            .allocationStrategy(AllocationStrategy.LOWEST_PRICE)
            .targetCapacity(targetCapacity)
            .spotPrice("0.48")
            .launchSpecifications(
              SpotFleetLaunchSpecification.builder()
                .imageId("ami-01a06cff1e109a0d2")
                .instanceType(AwsLib.instanceType)
                .keyName("Futon")
                .spotPrice("0.48")
                .iamInstanceProfile(_.arn("arn:aws:iam::270458169468:instance-profile/ecsInstanceRole"))
                .securityGroups(GroupIdentifier.builder().groupId("sg-e882ee83").build())
                .userData(Base64.getEncoder.encodeToString(userData.getBytes(StandardCharsets.UTF_8)
                )).build()
            )
        ).build()
    )
    Await.result(prog.toScala, Duration.Inf)
  }
}

