package com.example.seckill.service;

import com.example.seckill.dto.LoginRequestDTO;
import com.example.seckill.dto.LoginResponseDTO;

public interface AuthService {
    LoginResponseDTO login(LoginRequestDTO loginRequestDTO);

}
