package com.trex.server.service;

import com.trex.server.dto.DietLogRequest;
import com.trex.server.dto.DietLogResponse;
import com.trex.server.entity.DietLog;
import com.trex.server.entity.FoodItem;
import com.trex.server.entity.Meal;
import com.trex.server.entity.User;
import com.trex.server.exception.DuplicateResourceException;
import com.trex.server.exception.InvalidCredentialsException;
import com.trex.server.repository.DietLogRepository;
import com.trex.server.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class DietLogService {

    private final UserRepository userRepository;
    private final DietLogRepository dietLogRepository;

    public DietLogService(UserRepository userRepository, DietLogRepository dietLogRepository) {
        this.userRepository = userRepository;
        this.dietLogRepository = dietLogRepository;
    }

    @Transactional
    public DietLogResponse create(String loginId, DietLogRequest request) {
        User user = findUser(loginId);
        if (dietLogRepository.existsByUserIdAndLogDate(user.getId(), request.logDate())) {
            throw new DuplicateResourceException("해당 날짜의 식단 기록이 이미 있습니다");
        }

        DietLog log = DietLog.builder()
                .user(user)
                .logDate(request.logDate())
                .targetCal(request.targetCal())
                .targetCarb(request.targetCarb())
                .targetProtein(request.targetProtein())
                .targetFat(request.targetFat())
                .actualCal(request.actualCal())
                .actualCarb(request.actualCarb())
                .actualProtein(request.actualProtein())
                .actualFat(request.actualFat())
                .build();

        request.meals().forEach(mealRequest -> {
            Meal meal = Meal.builder().mealType(mealRequest.mealType()).build();
            mealRequest.foodItems().forEach(item -> meal.addFoodItem(
                    FoodItem.builder()
                            .foodName(item.foodName())
                            .calories(item.calories())
                            .carb(item.carb())
                            .protein(item.protein())
                            .fat(item.fat())
                            .build()
            ));
            log.addMeal(meal);
        });

        return DietLogResponse.from(dietLogRepository.save(log));
    }

    public List<DietLogResponse> getMine(String loginId) {
        User user = findUser(loginId);
        return dietLogRepository.findByUserIdOrderByLogDateDesc(user.getId()).stream()
                .map(DietLogResponse::from)
                .toList();
    }

    private User findUser(String loginId) {
        return userRepository.findByLoginId(loginId)
                .orElseThrow(() -> new InvalidCredentialsException("존재하지 않는 사용자입니다"));
    }
}
