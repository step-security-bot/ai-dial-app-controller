package com.epam.aidial.service;


import com.epam.aidial.config.AppConfiguration;
import com.epam.aidial.config.DockerAuthScheme;
import com.epam.aidial.kubernetes.knative.V1Service;
import com.epam.aidial.util.mapping.ListMapper;
import com.epam.aidial.util.mapping.MappingChain;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1EnvFromSource;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretEnvSource;
import io.kubernetes.client.openapi.models.V1SecretVolumeSource;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.epam.aidial.util.NamingUtils.appName;
import static com.epam.aidial.util.NamingUtils.buildJobName;
import static com.epam.aidial.util.NamingUtils.dialAuthSecretName;
import static com.epam.aidial.util.mapping.Mappers.CONTAINER_ARGS_FIELD;
import static com.epam.aidial.util.mapping.Mappers.CONTAINER_ENV_FIELD;
import static com.epam.aidial.util.mapping.Mappers.CONTAINER_ENV_FROM_FIELD;
import static com.epam.aidial.util.mapping.Mappers.CONTAINER_NAME;
import static com.epam.aidial.util.mapping.Mappers.CONTAINER_VOLUME_MOUNTS_FIELD;
import static com.epam.aidial.util.mapping.Mappers.ENV_VAR_NAME;
import static com.epam.aidial.util.mapping.Mappers.JOB_METADATA_FIELD;
import static com.epam.aidial.util.mapping.Mappers.JOB_SPEC_FIELD;
import static com.epam.aidial.util.mapping.Mappers.JOB_TEMPLATE_FIELD;
import static com.epam.aidial.util.mapping.Mappers.JOB_TEMPLATE_SPEC_FIELD;
import static com.epam.aidial.util.mapping.Mappers.POD_CONTAINERS_FIELD;
import static com.epam.aidial.util.mapping.Mappers.POD_INIT_CONTAINERS_FIELD;
import static com.epam.aidial.util.mapping.Mappers.POD_VOLUMES_FIELD;
import static com.epam.aidial.util.mapping.Mappers.SECRET_METADATA_FIELD;
import static com.epam.aidial.util.mapping.Mappers.SERVICE_METADATA_FIELD;
import static com.epam.aidial.util.mapping.Mappers.SERVICE_SPEC_FIELD;
import static com.epam.aidial.util.mapping.Mappers.SERVICE_TEMPLATE_FIELD;
import static com.epam.aidial.util.mapping.Mappers.SERVICE_TEMPLATE_SPEC_FIELD;
import static com.epam.aidial.util.mapping.Mappers.TEMPLATE_CONTAINERS_FIELD;
import static com.epam.aidial.util.mapping.Mappers.VOLUME_MOUNT_PATH;
import static com.epam.aidial.util.mapping.Mappers.VOLUME_NAME;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {
    private static final String DOCKER_CONFIG_KEY = "docker.config";

    private final RegistryService registryService;
    private final AppConfiguration appconfig;

    @Value("${app.template-container}")
    private final String pullerContainer;

    @Value("${app.builder-container}")
    private final String builderContainer;

    @Value("${app.service-container}")
    private final String serviceContainer;

    @Value("${app.docker-config-path}")
    private final String dockerConfigPath;

    public V1Secret dialAuthSecretConfig(String name, String apiKey, String jwt) {
        Map<String, String> creds = new HashMap<>();
        if (StringUtils.isNotBlank(apiKey)) {
            creds.put("API_KEY", apiKey);
        }
        if (StringUtils.isNotBlank(jwt)) {
            creds.put("JWT", jwt);
        }
        if (registryService.getAuthScheme() == DockerAuthScheme.BASIC) {
            creds.put(DOCKER_CONFIG_KEY, registryService.dockerConfig());
        }

        MappingChain<V1Secret> config = new MappingChain<>(this.appconfig.cloneSecretConfig());
        config.get(SECRET_METADATA_FIELD)
                .data()
                .setName(dialAuthSecretName(name));

        return config.data().stringData(creds);
    }

    public V1Job buildJobConfig(String name, String sources, String runtime) {
        String targetImage = registryService.fullImageName(name);
        log.info("Target image: {}", targetImage);

        AppConfiguration.RuntimeConfiguration runtimeConfig = this.appconfig.getRuntimes().get(runtime);
        if (runtimeConfig == null) {
            throw new IllegalArgumentException(
                    "Unsupported runtime: %s. Supported: %s".formatted(runtime, this.appconfig.getRuntimes().keySet()));
        }

        MappingChain<V1Job> config = new MappingChain<>(this.appconfig.cloneJobConfig());
        config.get(JOB_METADATA_FIELD)
                .data()
                .setName(buildJobName(name));
        MappingChain<V1PodSpec> podSpec = config.get(JOB_SPEC_FIELD)
                .get(JOB_TEMPLATE_FIELD)
                .get(JOB_TEMPLATE_SPEC_FIELD);
        MappingChain<V1Container> puller = podSpec.getList(POD_INIT_CONTAINERS_FIELD, CONTAINER_NAME)
                .get(pullerContainer);
        ListMapper<V1EnvVar> pullerEnvs = puller.getList(CONTAINER_ENV_FIELD, ENV_VAR_NAME);
        pullerEnvs.get("SOURCES")
                .data()
                .setValue(sources);
        pullerEnvs.get("PROFILE")
                .data()
                .setValue(runtimeConfig.getProfile());
        String secretName = dialAuthSecretName(name);
        puller.get(CONTAINER_ENV_FROM_FIELD)
                .data()
                .add(new V1EnvFromSource().secretRef(new V1SecretEnvSource().name(secretName)));
        MappingChain<V1Container> builder = podSpec.getList(POD_CONTAINERS_FIELD, CONTAINER_NAME)
                .get(builderContainer);
        builder.get(CONTAINER_ARGS_FIELD)
                .data()
                .addAll(List.of(
                        "--destination=%s".formatted(targetImage),
                        "--build-arg=BASE_IMAGE=%s".formatted(runtimeConfig.getImage())));
        if (registryService.getAuthScheme() == DockerAuthScheme.BASIC) {
            String volumeName = "secret-volume";
            podSpec.getList(POD_VOLUMES_FIELD, VOLUME_NAME)
                    .get(volumeName)
                    .data()
                    .setSecret(new V1SecretVolumeSource().secretName(secretName));
            V1VolumeMount volumeMount = builder.getList(CONTAINER_VOLUME_MOUNTS_FIELD, VOLUME_MOUNT_PATH)
                    .get(dockerConfigPath)
                    .data();
            volumeMount.setName(volumeName);
            volumeMount.setSubPath(DOCKER_CONFIG_KEY);
        }

        return config.data();
    }

    public V1Service appServiceConfig(String name, Map<String, String> env) {
        MappingChain<V1Service> config = new MappingChain<>(this.appconfig.cloneServiceConfig());
        config.get(SERVICE_METADATA_FIELD)
                .data()
                .setName(appName(name));
        MappingChain<V1Container> container = config.get(SERVICE_SPEC_FIELD)
                .get(SERVICE_TEMPLATE_FIELD)
                .get(SERVICE_TEMPLATE_SPEC_FIELD)
                .getList(TEMPLATE_CONTAINERS_FIELD, CONTAINER_NAME)
                .get(serviceContainer);

        container.data()
                .setImage(registryService.fullImageName(name));
        ListMapper<V1EnvVar> containerEnv = container.getList(CONTAINER_ENV_FIELD, ENV_VAR_NAME);

        env.forEach((key, value) -> containerEnv.get(key)
                .data()
                .setValue(value));
        return config.data();
    }
}
