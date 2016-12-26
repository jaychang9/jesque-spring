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

import static net.greghaines.jesque.utils.ResqueConstants.WORKER;
import static net.greghaines.jesque.worker.WorkerEvent.JOB_PROCESS;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import net.greghaines.jesque.Config;
import net.greghaines.jesque.Job;
import net.greghaines.jesque.worker.ReflectiveJobFactory;
import net.greghaines.jesque.worker.WorkerImpl;

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
                    Class<?> clazz = Class.forName(job.getClassName());//Lookup by Class type
                    String[] beanNames = applicationContext.getBeanNamesForType(clazz, true, false);
                    if (applicationContext.containsBeanDefinition(job.getClassName())) {
                        runnableJob = (Runnable) applicationContext.getBean(beanNames[0], job.getArgs());
                    } else {
                        if (beanNames != null && beanNames.length == 1) {
                            runnableJob = (Runnable) applicationContext.getBean(beanNames[0], job.getArgs());
                            doProcessInitRunableJob(job, runnableJob, clazz);	
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
    
    /**
     * Initialize the RunnableJob by runnableJobClazz and job 's paramater args
     */
	private void doProcessInitRunableJob(final Job job, Runnable runnableJob, Class<?> runnableJobClazz)
			throws IllegalAccessException {
		Field[] allFields = runnableJobClazz.getDeclaredFields();
		if(allFields.length > 0){
			List<Field> needFields = doFindNeedField(allFields);
			//Check the Job Class's Field num is or not match the Job instance's arg num
			if(needFields.size() != job.getArgs().length){
				throw new RuntimeException("The "+runnableJobClazz.getName()+" Class's Fields(not include marked as @Resource and @Autowired Fields) quantity not match the Job instance args quantity,please check the Class ["+runnableJobClazz.getName()+"]");
			}
			for(int i = 0 ; i < needFields.size() ; i++){
				Field field = needFields.get(i);
				Class<?> fieldType = field.getType();
				field.setAccessible(true);
				Object arg = job.getArgs()[i];
				if( fieldType != job.getArgs()[i].getClass()){
					//Fix when the Job args parameter has java.lang.Long or java.lang.Float type,jackson framework will the change the java.lang.Long to java.lang.Integer,
					// and the java.lang.Float to java.lang.Double
					if(fieldType == Long.class){
						field.set(runnableJob, Long.valueOf(arg.toString()));
					}else if(fieldType == Float.class){
						field.set(runnableJob, Float.valueOf(arg.toString()));
					}else{
						field.set(runnableJob, arg);
					}
				}
				else{
					field.set(runnableJob, arg);
				}
			}
		}
	}
    
	/**
     * Find the needed Field that are not marked by @Resource or @Autowired annotation
     */
    private List<Field> doFindNeedField(Field[]  fields) {
		List<Field> needFields = new ArrayList<Field>();
		for(int i = 0 ; i < fields.length ; i ++){
			Field field = fields[i];
			if(field.getDeclaredAnnotation(Autowired.class) != null || field.getDeclaredAnnotation(Resource.class) != null){
				continue;
			}
			needFields.add(field);
		}
		return needFields;
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
