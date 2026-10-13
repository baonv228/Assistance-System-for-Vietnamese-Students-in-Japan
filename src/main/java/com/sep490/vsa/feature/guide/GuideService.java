package com.sep490.vsa.feature.guide;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GuideService {

    private final GuideRepository guideRepository;

    public GuideService(GuideRepository guideRepository) {
        this.guideRepository = guideRepository;
    }

    public List<GuideResponse> listPublished(String category) {
        List<Guide> guides = category == null || category.isBlank()
                ? guideRepository.findByPublishedTrue()
                : guideRepository.findByPublishedTrueAndCategoryIgnoreCase(category);
        return guides.stream().map(GuideResponse::from).toList();
    }
}
