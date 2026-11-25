package com.sep490.vsa;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=1",
        "app.media.local-dir=target/test-uploads",
        "app.places.osm.enabled=false",
        "server.port=0"
})
@ActiveProfiles("local")
class VietnameseStudentAssistanceApplicationTests {

    @Test
    void contextLoads() {
    }
}
