package org.apache.rocketmq.example;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

public class SimpleConsumerDemo {
    public static void main(String[] args) throws ClientException {
        ClientServiceProvider provider1 = ClientServiceProvider.loadService();
        String topic = "Your Topic";
        FilterExpression filterExpression = new FilterExpression("Your Filter Tag", FilterExpressionType.TAG);

        SimpleConsumer simpleConsumer = provider1.newSimpleConsumerBuilder()
                //设置消费者分组。
                .setConsumerGroup("Your ConsumerGroup")
                //设置接入点。
                .setClientConfiguration(ClientConfiguration.newBuilder().setEndpoints("Your Endpoint").build())
                //设置预绑定的订阅关系。
                .setSubscriptionExpressions(Collections.singletonMap(topic, filterExpression))
                .build();
        List<MessageView> messageViewList = null;
        try {
            //SimpleConsumer需要主动获取消息，并处理。
            messageViewList = simpleConsumer.receive(10, Duration.ofSeconds(30));
            messageViewList.forEach(messageView -> {
                System.out.println(messageView);
                //消费处理完成后，需要主动调用ACK提交消费结果。
                try {
                    simpleConsumer.ack(messageView);
                } catch (ClientException e) {
                    e.printStackTrace();
                }
            });
        } catch (ClientException e) {
            //如果遇到系统流控等原因造成拉取失败，需要重新发起获取消息请求。
            e.printStackTrace();
        }
    }
}
