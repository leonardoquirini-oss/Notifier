package it.gruppobernardini.switchmail.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduler esplicito.
 *
 * <p>Trappola: il TaskScheduler di default di Spring e' <b>mono-thread</b>. Con quello, lo sweeper
 * dei retry si accoderebbe dietro un poll IMAP lento e i due interferirebbero in silenzio.
 *
 * <p>Con l'attesa in shutdown, un SIGTERM durante un poll finisce la mail in volo invece di lasciare
 * una riga IN_PROGRESS orfana da recuperare al riavvio.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("sm-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
