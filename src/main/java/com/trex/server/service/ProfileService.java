package com.trex.server.service;

import com.trex.server.dto.ProfileRequest;
import com.trex.server.dto.ProfileResponse;
import com.trex.server.entity.User;
import com.trex.server.entity.UserProfile;
import com.trex.server.exception.DuplicateResourceException;
import com.trex.server.exception.ResourceNotFoundException;
import com.trex.server.repository.UserProfileRepository;
import com.trex.server.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProfileService {

    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;

    public ProfileService(UserRepository userRepository, UserProfileRepository profileRepository) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
    }

    @Transactional
    public ProfileResponse create(String loginId, ProfileRequest request) {
        User user = userRepository.getByLoginIdOrThrow(loginId);
        if (profileRepository.existsByUserId(user.getId())) {
            throw new DuplicateResourceException("이미 등록된 프로필이 있습니다");
        }

        UserProfile profile = UserProfile.builder()
                .user(user)
                .gender(request.gender())
                .place(request.place())
                .fitnessGoal(request.fitnessGoal())
                .workoutDays(request.workoutDays())
                .availableEquip(request.availableEquip())
                .height(request.height())
                .weight(request.weight())
                .age(request.age())
                .build();

        return ProfileResponse.from(profileRepository.save(profile));
    }

    public ProfileResponse getMine(String loginId) {
        User user = userRepository.getByLoginIdOrThrow(loginId);
        UserProfile profile = profileRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("등록된 프로필이 없습니다"));
        return ProfileResponse.from(profile);
    }
}
