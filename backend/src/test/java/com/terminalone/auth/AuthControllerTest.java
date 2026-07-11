package com.terminalone.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.terminalone.auth.dto.LoginRequest;
import com.terminalone.auth.dto.LoginResponse;
import com.terminalone.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private JwtService jwtService;

    private AuthenticationManager authenticationManager;

    private AuthController authController;

    private MockMvc mockMvc;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        authenticationManager = mock(AuthenticationManager.class);
        jwtService = mock(JwtService.class);
        authController = new AuthController(authenticationManager, jwtService);
        mockMvc = MockMvcBuilders.standaloneSetup(authController).build();
    }

    @Test
    void login_callsAuthManager_andReturnsTokenWithValidCreds() throws Exception {
        Authentication authentication = mock(Authentication.class);
        Instant expiry = Instant.now().plusSeconds(3600);
        when(authentication.getName()).thenReturn("username");
        when(jwtService.generateToken("username")).thenReturn("Bearer some.jwt.token");
        when(jwtService.extractExpiration("Bearer some.jwt.token")).thenReturn(expiry);

        LoginResponse expected = new LoginResponse("Bearer some.jwt.token", "username", expiry);

        LoginRequest loginRequest = new LoginRequest("username", "pwd");

        UsernamePasswordAuthenticationToken userPassToken =
                new UsernamePasswordAuthenticationToken("username", "pwd");

        when(authenticationManager.authenticate(userPassToken))
                .thenReturn(authentication);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest))
                )
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(expected)));

        verify(authenticationManager).authenticate(userPassToken);
        verify(jwtService).generateToken("username");
        verify(jwtService).extractExpiration("Bearer some.jwt.token");
    }

    @Test
    void login_badCredentials_returns401() throws Exception {
        LoginRequest loginRequest = new LoginRequest("username", "wrongPwd");

        UsernamePasswordAuthenticationToken badAuthToken =
                new UsernamePasswordAuthenticationToken("username", "wrongPwd");
        when(authenticationManager.authenticate(badAuthToken))
                .thenThrow(new BadCredentialsException("Unautorized"));

        mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest))
                )
                .andExpect(status().isUnauthorized());
        verify(authenticationManager).authenticate(badAuthToken);
    }
}