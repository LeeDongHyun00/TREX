package com.trex.server.entity;

import com.trex.server.converter.IntegerListJsonConverter;
import com.trex.server.converter.StringListJsonConverter;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Entity
@Table(name = "USER_PROFILES")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false)
    private String gender;

    @Column(nullable = false)
    private String place;

    @Column(nullable = false)
    private String fitnessGoal;

    // 요일 인덱스 배열(0=월 ~ 6=일)을 JSON 문자열로 저장한다.
    @Convert(converter = IntegerListJsonConverter.class)
    @Column(name = "workout_days", nullable = false, length = 200)
    private List<Integer> workoutDays;

    // 보유 장비 라벨 배열을 JSON 문자열로 저장한다. 맨몸 운동이면 빈 배열.
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "available_equip", nullable = false, length = 1000)
    private List<String> availableEquip;

    @Column(nullable = false)
    private Integer height;

    @Column(nullable = false)
    private Integer weight;

    @Column(nullable = false)
    private Integer age;

    @Builder
    public UserProfile(
            User user,
            String gender,
            String place,
            String fitnessGoal,
            List<Integer> workoutDays,
            List<String> availableEquip,
            Integer height,
            Integer weight,
            Integer age
    ) {
        this.user = user;
        this.gender = gender;
        this.place = place;
        this.fitnessGoal = fitnessGoal;
        this.workoutDays = workoutDays;
        this.availableEquip = availableEquip;
        this.height = height;
        this.weight = weight;
        this.age = age;
    }
}
