/*
 * Copyright 2012
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.lariverosc.jesquespring;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import net.greghaines.jesque.Config;
import net.greghaines.jesque.Job;
import static net.greghaines.jesque.worker.WorkerEvent.JOB_PROCESS;
import static net.greghaines.jesque.utils.ResqueConstants.WORKER;
import net.greghaines.jesque.worker.ReflectiveJobFactory;
import net.greghaines.jesque.worker.WorkerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 *
 * @author Alejandro <lariverosc@gmail.com>
 */
public class SpringWorker extends WorkerImpl implements ApplicationContextAware {

    private final Logger logger = LoggerFactory.getLogger(SpringWorker.class);

    private ApplicationContext applicationContext;

    private final AtomicBoolean processingJob = new AtomicBoolean(false);

    private final String name;

    @Override
    public boolean isProcessingJob() {
        return this.processingJob.get();
    }

    /**
     *
     * @param config used to create a connection to Redis
     * @param queues the list of queues to poll
     */
    public SpringWorker(final Config config, final Collection<String> queues) {
        super(config, queues, new ReflectiveJobFactory() );
        this.name = createName();
    }

    @Override
    protected void process(final Job job, final String curQueue) {
        logger.info("Process new Job from queue {}", curQueue);
        try {
            Runnable runnableJob = null;
            if (applicationContext.containsBeanDefinition(job.getClassName())) {//Lookup by bean Id
                runnableJob = (Runnable) applicationContext.getBean(job.getClassName(), job.getArgs());
            } else {
                try {
                    Class clazz = Class.forName(job.getClassName());//Lookup by Class type
                    String[] beanNames = applicationContext.getBeanNamesForType(clazz, true, false);
                    if (applicationContext.containsBeanDefinition(job.getClassName())) {
                        runnableJob = (Runnable) applicationContext.getBean(beanNames[0], job.getArgs());
                    } else {
                        if (beanNames != null && beanNames.length == 1) {
                            runnableJob = (Runnable) applicationContext.getBean(beanNames[0], job.getArgs());
                        }
                    }
                } catch (ClassNotFoundException cnfe) {
                    logger.error("Not bean Id or class definition found {}", job.getClassName());
                    throw new Exception("Not bean Id or class definition found " + job.getClassName());
                }
            }
            if (runnableJob != null) {
                this.processingJob.set(true);
                this.listenerDelegate.fireEvent(JOB_PROCESS, this, curQueue, job, null, null, null);
                this.jedis.set(key(WORKER, this.name), statusMsg(curQueue, job));
                if (isThreadNameChangingEnabled()) {
                    renameThread("Processing " + curQueue + " since " + System.currentTimeMillis());
                }
                final Object result = execute(job, curQueue, runnableJob);
                success(job, runnableJob, result, curQueue);
            }
        } catch (Exception e) {
            logger.error("Error while processing the job: " + job.getClassName(), e);
            failure(e, job, curQueue);
        } finally {
            this.jedis.del(key(WORKER, this.name));
            this.processingJob.set(false);
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * Convenient initialization method for the Spring container
     */
    public void init() {
        logger.info("Start a new thread for SpringWorker");
        new Thread(this).start();
    }

    /**
     * Convenient destroy method for the Spring container
     */
    public void destroy() {
        logger.info("End the SpringWorker thread");
        end(true);
    }
}
