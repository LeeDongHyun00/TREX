package com.trex.server.dto;

import com.trex.server.entity.User;

public record UserResponse(
        Integer id,
        String loginId,
        String name,
        String email
) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getLoginId(), user.getName(), user.getEmail());
    }
}
