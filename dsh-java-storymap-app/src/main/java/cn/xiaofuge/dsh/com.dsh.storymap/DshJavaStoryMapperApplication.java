package cn.xiaofuge.dsh.com.dsh.storymap;

import cn.xiaofuge.dsh.com.dsh.storymap.config.StoryMapperProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(StoryMapperProperties.class)
public class DshJavaStoryMapperApplication {

    public static void main(String[] args) {
        SpringApplication.run(DshJavaStoryMapperApplication.class, args);
    }
}
