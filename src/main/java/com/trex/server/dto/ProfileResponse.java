package com.trex.server.dto;

import com.trex.server.entity.UserProfile;

import java.util.List;

public record ProfileResponse(
        Integer id,
        String fitnessGoal,
        List<Integer> workoutDays,
        String place,
        String gender,
        List<String> availableEquip,
        Integer height,
        Integer weight,
        Integer age
) {

    public static ProfileResponse from(UserProfile profile) {
        return new ProfileResponse(
                profile.getId(),
                profile.getFitnessGoal(),
                profile.getWorkoutDays(),
                profile.getPlace(),
                profile.getGender(),
                profile.getAvailableEquip(),
                profile.getHeight(),
                profile.getWeight(),
                profile.getAge()
        );
    }
}
