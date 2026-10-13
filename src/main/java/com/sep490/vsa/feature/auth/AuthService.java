package com.sep490.vsa.feature.auth;

import com.sep490.vsa.feature.user.User;
import com.sep490.vsa.feature.user.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("Username already exists");
        }

        User user = userRepository.save(new User(
                null,
                request.fullName(),
                request.username(),
                request.email(),
                request.password(),
                "STUDENT"
        ));
        return toResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid username or password"));

        if (!user.getPassword().equals(request.password())) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        return toResponse(user);
    }

    private AuthResponse toResponse(User user) {
        return new AuthResponse(
                user.getId(),
                user.getFullName(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                "base-token-" + user.getId()
        );
    }
}
