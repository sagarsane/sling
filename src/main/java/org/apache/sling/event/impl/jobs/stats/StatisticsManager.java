/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.event.impl.jobs.stats;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.SlingConstants;
import org.apache.sling.event.EventUtil;
import org.apache.sling.event.impl.jobs.JobManagerConfiguration;
import org.apache.sling.event.impl.jobs.TestLogger;
import org.apache.sling.event.impl.jobs.Utility;
import org.apache.sling.event.impl.jobs.config.QueueConfigurationManager;
import org.apache.sling.event.impl.jobs.config.QueueConfigurationManager.QueueInfo;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.NotificationConstants;
import org.apache.sling.event.jobs.Statistics;
import org.apache.sling.event.jobs.TopicStatistics;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(immediate=true)
@Service(value={EventHandler.class, StatisticsManager.class})
@Properties({
    @Property(name=EventConstants.EVENT_TOPIC,
          value={SlingConstants.TOPIC_RESOURCE_ADDED,
                 NotificationConstants.TOPIC_JOB_STARTED,
                 NotificationConstants.TOPIC_JOB_CANCELLED,
                 NotificationConstants.TOPIC_JOB_FAILED,
                 NotificationConstants.TOPIC_JOB_FINISHED,
                 NotificationConstants.TOPIC_JOB_REMOVED})
})
// TODO register event handlers on activate to allow for filters!
public class StatisticsManager implements EventHandler {

    /** Logger. */
    private final Logger logger = new TestLogger(LoggerFactory.getLogger(this.getClass()));

    @Reference
    private JobManagerConfiguration configuration;

    @Reference
    private QueueConfigurationManager queueConfigurationManager;

    /** Current statistics. */
    private final StatisticsImpl baseStatistics = new StatisticsImpl();

    /** Statistics per topic. */
    private final ConcurrentMap<String, TopicStatistics> topicStatistics = new ConcurrentHashMap<String, TopicStatistics>();

    /** Statistics per queue. */
    private final ConcurrentMap<String, Statistics> queueStatistics = new ConcurrentHashMap<String, Statistics>();

    public Statistics getOverallStatistics() {
        return this.baseStatistics;
    }

    public Map<String, TopicStatistics> getTopicStatistics() {
        return topicStatistics;
    }

    public Statistics getQueueStatistics(final String queueName) {
        Statistics queueStats = queueStatistics.get(queueName);
        if ( queueStats == null ) {
            queueStats = new StatisticsImpl();
        }
        return queueStats;
    }

    private StatisticsImpl getStatisticsForQueue(final String queueName) {
        if ( queueName == null ) {
            return null;
        }
        StatisticsImpl queueStats = (StatisticsImpl)queueStatistics.get(queueName);
        if ( queueStats == null ) {
            queueStatistics.putIfAbsent(queueName, new StatisticsImpl());
            queueStats = (StatisticsImpl)queueStatistics.get(queueName);
        }
        return queueStats;
    }

    @Override
    public void handleEvent(final Event event) {
        if ( SlingConstants.TOPIC_RESOURCE_ADDED.equals(event.getTopic()) ) {
            final String path = (String) event.getProperty(SlingConstants.PROPERTY_PATH);
            if ( this.configuration.isLocalJob(path) ) {
                final int topicStart = this.configuration.getLocalJobsPath().length() + 1;
                final int topicEnd = path.indexOf("/", topicStart);
                final String topic;
                if ( topicEnd == -1 ) {
                    topic = path.substring(topicStart).replace('.', '/');
                } else {
                    topic = path.substring(topicStart, topicEnd).replace('.', '/');
                }
                this.baseStatistics.incQueued();
                final QueueInfo info = this.queueConfigurationManager.getQueueInfo(topic);
                final StatisticsImpl queueStats = getStatisticsForQueue(info.queueName);
                queueStats.incQueued();
            }
        } else {
            if ( EventUtil.isLocal(event) ) {
                // job notifications
                final String topic = (String)event.getProperty(NotificationConstants.NOTIFICATION_PROPERTY_JOB_TOPIC);
                if ( topic != null ) { // this is just a sanity check
                    final String queueName = (String)event.getProperty(Job.PROPERTY_JOB_QUEUE_NAME);
                    final StatisticsImpl queueStats = getStatisticsForQueue(queueName);

                    TopicStatisticsImpl ts = (TopicStatisticsImpl)this.topicStatistics.get(topic);
                    if ( ts == null ) {
                        this.topicStatistics.putIfAbsent(topic, new TopicStatisticsImpl(topic));
                        ts = (TopicStatisticsImpl)this.topicStatistics.get(topic);
                    }

                    if ( event.getTopic().equals(NotificationConstants.TOPIC_JOB_CANCELLED) ) {
                        ts.addCancelled();
                        this.baseStatistics.cancelledJob();
                        if ( queueStats != null ) {
                            queueStats.cancelledJob();
                        }
                    } else if ( event.getTopic().equals(NotificationConstants.TOPIC_JOB_FAILED) ) {
                        ts.addFailed();
                        this.baseStatistics.failedJob();
                        if ( queueStats != null ) {
                            queueStats.failedJob();
                        }
                    } else if ( event.getTopic().equals(NotificationConstants.TOPIC_JOB_FINISHED) ) {
                        final Long time = (Long)event.getProperty(Utility.PROPERTY_TIME);
                        ts.addFinished(time == null ? -1 : time);
                        this.baseStatistics.finishedJob(time == null ? -1 : time);
                        if ( queueStats != null ) {
                            queueStats.finishedJob(time == null ? -1 : time);
                        }
                    } else if ( event.getTopic().equals(NotificationConstants.TOPIC_JOB_STARTED) ) {
                        final Long time = (Long)event.getProperty(Utility.PROPERTY_TIME);
                        ts.addActivated(time == null ? -1 : time);
                        this.baseStatistics.addActive(time == null ? -1 : time);
                        if ( queueStats != null ) {
                            queueStats.addActive(time == null ? -1 : time);
                        }
                    } else if ( NotificationConstants.TOPIC_JOB_REMOVED.equals(event.getTopic()) ) {
                        this.baseStatistics.decQueued();
                        this.baseStatistics.cancelledJob();
                        if ( queueStats != null ) {
                            queueStats.decQueued();
                            queueStats.cancelledJob();
                        }
                    }
                }
            }
        }
    }
}
