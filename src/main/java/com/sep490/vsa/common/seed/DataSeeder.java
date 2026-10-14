package com.sep490.vsa.common.seed;

import com.sep490.vsa.feature.guide.Guide;
import com.sep490.vsa.feature.guide.GuideRepository;
import com.sep490.vsa.feature.scholarship.Scholarship;
import com.sep490.vsa.feature.scholarship.ScholarshipRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

// Demo data for the starter H2 entities. Their tables do not exist in the MySQL schema
// (seed data now lives in the Flyway migrations), so this only runs with the "starter-demo" profile.
@Profile("starter-demo")
@Component
public class DataSeeder implements CommandLineRunner {

    private final GuideRepository guideRepository;
    private final ScholarshipRepository scholarshipRepository;

    public DataSeeder(
            GuideRepository guideRepository,
            ScholarshipRepository scholarshipRepository
    ) {
        this.guideRepository = guideRepository;
        this.scholarshipRepository = scholarshipRepository;
    }

    @Override
    public void run(String... args) {
        if (guideRepository.count() == 0) {
            guideRepository.save(new Guide(null, "Thu tuc visa du hoc",
                    "Checklist ho so, thoi han gia han va cac luu y khi nop tai Nhat.",
                    "VISA", true));
            guideRepository.save(new Guide(null, "Tim nha o lan dau",
                    "Cach chuan bi giay to, tien dau vao va cau hoi can hoi cong ty bat dong san.",
                    "LIFE", true));
        }

        if (scholarshipRepository.count() == 0) {
            scholarshipRepository.save(new Scholarship(null, "JASSO Honors Scholarship",
                    "Hoc bong danh cho sinh vien quoc te co thanh tich hoc tap tot.",
                    "JASSO", LocalDate.now().plusMonths(2), true));
            scholarshipRepository.save(new Scholarship(null, "University Tuition Reduction",
                    "Mien giam hoc phi theo quy dinh cua tung truong.",
                    "UNIVERSITY", LocalDate.now().plusMonths(1), true));
        }
        // Accounts are created through sign-up; there is no demo user with a plain-text password any more.
    }
}
