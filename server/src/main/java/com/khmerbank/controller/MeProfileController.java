package com.khmerbank.controller;

import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.model.User;
import com.khmerbank.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@Tag(name = "Me — Profile")
public class MeProfileController {

    private final UserRepository users;

    @GetMapping("/profile")
    @Operation(summary = "Current user profile")
    public ApiResponse<Map<String, Object>> profile(@AuthenticationPrincipal User user) {
        return ApiResponse.ok(toView(user));
    }

    @PatchMapping("/profile")
    @Operation(summary = "Update name / phone / company")
    public ApiResponse<Map<String, Object>> update(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UpdateProfileBody body) {
        if (body.fullName != null && !body.fullName.isBlank())
            user.setFullName(body.fullName.trim());
        if (body.phone != null) user.setPhone(body.phone.trim());
        if (body.company != null) user.setCompany(body.company.trim());
        users.save(user);
        return ApiResponse.ok(toView(user), "Profile updated");
    }

    private Map<String, Object> toView(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            u.getId());
        m.put("email",         u.getEmail());
        m.put("fullName",      u.getFullName());
        m.put("phone",         u.getPhone());
        m.put("company",       u.getCompany());
        m.put("role",          u.getRole().name());
        m.put("status",        u.getStatus());
        m.put("avatarUrl",     u.getAvatarUrl());
        m.put("emailVerified", u.isEmailVerified());
        m.put("createdAt",     u.getCreatedAt());
        m.put("lastLoginAt",   u.getLastLoginAt());
        return m;
    }

    public static class UpdateProfileBody {
        @Size(max = 100) public String fullName;
        @Size(max = 20)  public String phone;
        @Size(max = 100) public String company;
    }
}
