package io.eventuate.messaging.redis.integrationtests;

import com.google.common.collect.ImmutableSet;
import io.eventuate.messaging.partition.management.CoordinatorFactory;
import io.eventuate.messaging.partition.management.CoordinatorFactoryImpl;
import io.eventuate.messaging.partition.management.tests.AbstractMessagingTest;
import io.eventuate.messaging.redis.common.CommonRedisConfiguration;
import io.eventuate.messaging.redis.common.RedissonClients;
import io.eventuate.messaging.redis.consumer.*;
import io.eventuate.messaging.redis.leadership.RedisLeaderSelector;
import io.eventuate.messaging.redis.producer.EventuateRedisProducer;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.concurrent.*;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = MessagingTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class MessagingTest extends AbstractMessagingTest {

  @Configuration
  @EnableAutoConfiguration
  @Import(CommonRedisConfiguration.class)
  public static class Config {
  }

  @Autowired
  private RedisTemplate<String, String> redisTemplate;

  @Autowired
  private RedissonClients redissonClients;

  @Override
  protected TestSubscription subscribe(int partitionCount) {
    ConcurrentLinkedQueue<Integer> messageQueue = new ConcurrentLinkedQueue<>();

    MessageConsumerRedisImpl consumer = createConsumer(partitionCount);

    consumer.subscribe(subscriberId, ImmutableSet.of(destination), message ->
            messageQueue.add(Integer.parseInt(message.getPayload())));

    TestSubscription testSubscription = new TestSubscription(consumer, messageQueue);

    consumer.setSubscriptionLifecycleHook((channel, subscriptionId, currentPartitions) -> {
      testSubscription.setCurrentPartitions(currentPartitions);
    });
    consumer.setLeaderHook((leader, subscriptionId) -> testSubscription.setLeader(leader));

    return testSubscription;
  }

  private MessageConsumerRedisImpl createConsumer(int partitionCount) {

    CoordinatorFactory coordinatorFactory = new CoordinatorFactoryImpl(new RedisAssignmentManager(redisTemplate, 3600000),
            (groupId, memberId, assignmentUpdatedCallback) -> new RedisAssignmentListener(redisTemplate, groupId, memberId, 50, assignmentUpdatedCallback),
            (groupId, memberId, groupMembersUpdatedCallback) -> new RedisMemberGroupManager(redisTemplate, groupId, memberId,50, groupMembersUpdatedCallback),
            (lockId, leaderId, leaderSelectedCallback, leaderRemovedCallback) -> new RedisLeaderSelector(redissonClients, lockId, leaderId,10000, leaderSelectedCallback, leaderRemovedCallback),
            (groupId, memberId) -> new RedisGroupMember(redisTemplate, groupId, memberId, 1000),
            partitionCount);

    MessageConsumerRedisImpl messageConsumerRedis = new MessageConsumerRedisImpl(subscriptionIdSupplier,
            consumerIdSupplier.get(),
            redisTemplate,
            coordinatorFactory,
            100,
            100);

    applicationContext.getAutowireCapableBeanFactory().autowireBean(messageConsumerRedis);

    return messageConsumerRedis;
  }

  @Override
  protected void sendMessages(int messageCount, int partitions) {
    EventuateRedisProducer eventuateRedisProducer = new EventuateRedisProducer(redisTemplate, partitions);

    for (int i = 0; i < messageCount; i++) {
      eventuateRedisProducer.send(destination,
              String.valueOf(i),
              String.valueOf(i));
    }
  }
}
