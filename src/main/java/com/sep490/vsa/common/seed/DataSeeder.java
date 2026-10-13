package com.sep490.vsa.common.seed;

import com.sep490.vsa.feature.guide.Guide;
import com.sep490.vsa.feature.guide.GuideRepository;
import com.sep490.vsa.feature.scholarship.Scholarship;
import com.sep490.vsa.feature.scholarship.ScholarshipRepository;
import com.sep490.vsa.feature.user.User;
import com.sep490.vsa.feature.user.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class DataSeeder implements CommandLineRunner {

    private final GuideRepository guideRepository;
    private final ScholarshipRepository scholarshipRepository;
    private final UserRepository userRepository;

    public DataSeeder(
            GuideRepository guideRepository,
            ScholarshipRepository scholarshipRepository,
            UserRepository userRepository
    ) {
        this.guideRepository = guideRepository;
        this.scholarshipRepository = scholarshipRepository;
        this.userRepository = userRepository;
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

        if (userRepository.count() == 0) {
            userRepository.save(new User(null, "Demo Student", "demo", "demo@student.vn", "123456", "STUDENT"));
        }
    }
}
