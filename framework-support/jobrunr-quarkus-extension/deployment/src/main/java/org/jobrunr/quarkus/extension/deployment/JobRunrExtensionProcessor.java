package org.jobrunr.quarkus.extension.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.recording.RecorderContext;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.DotName;
import org.jboss.jandex.MethodInfo;
import org.jobrunr.jobs.JobDetails;
import org.jobrunr.quarkus.annotations.Recurring;
import org.jobrunr.quarkus.autoconfigure.JobRunrProducer;
import org.jobrunr.quarkus.autoconfigure.JobRunrStarter;
import org.jobrunr.quarkus.autoconfigure.recording.CronExpressionSubstitution;
import org.jobrunr.quarkus.autoconfigure.storage.JobRunrElasticSearchStorageProviderProducer;
import org.jobrunr.quarkus.autoconfigure.storage.JobRunrInMemoryStorageProviderProducer;
import org.jobrunr.quarkus.autoconfigure.storage.JobRunrMongoDBStorageProviderProducer;
import org.jobrunr.quarkus.autoconfigure.storage.JobRunrSqlStorageProviderProducer;
import org.jobrunr.scheduling.JobRunrRecorder;
import org.jobrunr.scheduling.cron.CronExpression;

import java.time.ZoneId;
import java.util.*;

import static java.util.stream.Collectors.toSet;

class JobRunrExtensionProcessor {

    private static final String FEATURE = "jobrunr";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem produce(Capabilities capabilities, CombinedIndexBuildItem index) {
        Set<Class> beanClasses = new HashSet<>();
        beanClasses.add(JobRunrProducer.class);
        beanClasses.add(JobRunrStarter.class);
        beanClasses.add(storageProviderClass(capabilities));
        beanClasses.add(jsonMapperClass(capabilities));

        System.out.println("========================================================");
        System.out.println(capabilities.getCapabilities());
        System.out.println(beanClasses);
        //System.out.println(index.getIndex().getKnownClasses());

        System.out.println("========================================================");

        return AdditionalBeanBuildItem.builder()
                .setUnremovable()
                .addBeanClasses(beanClasses.stream().map(Class::getName).collect(toSet()))
                .build();
    }

    @Record(ExecutionTime.RUNTIME_INIT)
    @BuildStep
    void findRecurringJobAnnotationsAndScheduleThem(RecorderContext recorderContext, CombinedIndexBuildItem index, BeanContainerBuildItem beanContainer, JobRunrRecorder recorder) throws Exception {
        System.out.println("===========================================");
        System.out.println("Scanning for recurring jobs...");
        System.out.println("===========================================");

        recorderContext.registerNonDefaultConstructor(JobDetails.class.getDeclaredConstructor(String.class, String.class, String.class, List.class), jobDetails -> Arrays.asList(
                jobDetails.getClassName(),
                jobDetails.getStaticFieldName(),
                jobDetails.getMethodName(),
                jobDetails.getJobParameters()
        ));
        recorderContext.registerSubstitution(CronExpression.class, String.class, CronExpressionSubstitution.class);

        for (AnnotationInstance recurringJobAnnotation : index.getIndex().getAnnotations(DotName.createSimple(Recurring.class.getName()))) {
            AnnotationTarget annotationTarget = recurringJobAnnotation.target();
            if (AnnotationTarget.Kind.METHOD.equals(annotationTarget.kind())) {
                final String id = getId(recurringJobAnnotation);
                final JobDetails jobDetails = getJobDetails(recurringJobAnnotation);
                final CronExpression cron = getCronExpression(recurringJobAnnotation);
                final ZoneId zoneId = getZoneId(recurringJobAnnotation);
                System.out.println("===========================================");
                System.out.println("Found recurring job: " + id + "; " + cron.getExpression() + "; " + jobDetails);
                System.out.println("===========================================");

                recorder.schedule(beanContainer.getValue(), id, jobDetails, cron, zoneId);
            }
        }
    }

    private String getId(AnnotationInstance recurringJobAnnotation) {
        if (recurringJobAnnotation.value("id") != null) {
            return recurringJobAnnotation.value("id").asString();
        }
        return null;
    }

    private JobDetails getJobDetails(AnnotationInstance recurringJobAnnotation) {
        final MethodInfo methodInfo = recurringJobAnnotation.target().asMethod();
        if (!methodInfo.parameters().isEmpty()) {
            throw new IllegalStateException("Methods annotated with " + Recurring.class.getName() + " can not have parameters.");
        }
        return new JobDetails(
                methodInfo.declaringClass().name().toString(),
                null,
                methodInfo.name(),
                new ArrayList<>()
        );
    }

    private ZoneId getZoneId(AnnotationInstance recurringJobAnnotation) {
        if (recurringJobAnnotation.value("zoneId") != null) {
            return ZoneId.of(recurringJobAnnotation.value("zoneId").asString());
        }
        return ZoneId.systemDefault();
    }

    private CronExpression getCronExpression(AnnotationInstance recurringJobAnnotation) {
        return CronExpression.create(recurringJobAnnotation.value("cron").asString());
    }

    private Class<?> jsonMapperClass(Capabilities capabilities) {
        if (capabilities.isPresent("io.quarkus.jsonb")) {
            return JobRunrProducer.JobRunrJsonBJsonMapperProducer.class;
        } else if (capabilities.isPresent("io.quarkus.jackson")) {
            return JobRunrProducer.JobRunrJacksonJsonMapperProducer.class;
        }
        throw new IllegalStateException("Either JSON-B or Jackson should be added via a Quarkus extension");
    }

    private Class<?> storageProviderClass(Capabilities capabilities) {
        if (capabilities.isPresent("io.quarkus.agroal")) {
            return JobRunrSqlStorageProviderProducer.class;
        } else if (capabilities.isPresent("io.quarkus.mongodb-client")) {
            return JobRunrMongoDBStorageProviderProducer.class;
        } else if (capabilities.isPresent("io.quarkus.elasticsearch-rest-high-level-client")) {
            return JobRunrElasticSearchStorageProviderProducer.class;
        } else {
            return JobRunrInMemoryStorageProviderProducer.class;
        }
    }
}