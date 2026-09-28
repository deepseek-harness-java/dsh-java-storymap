package cn.xiaofuge.dsh.com.dsh.storymap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dsh.storymap")
public record StoryMapperProperties(
        String dataDirectory,
        Harness harness
) {
    public record Harness(String baseUrl) {
    }
}
