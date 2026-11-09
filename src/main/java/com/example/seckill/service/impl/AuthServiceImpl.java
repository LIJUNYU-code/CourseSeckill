package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.common.ResultCode;
import com.example.seckill.dto.LoginRequestDTO;
import com.example.seckill.dto.LoginResponseDTO;
import com.example.seckill.entity.Student;
import com.example.seckill.entity.SysUser;
import com.example.seckill.exception.BusinessException;
import com.example.seckill.mapper.StudentMapper;
import com.example.seckill.mapper.SysUserMapper;
import com.example.seckill.security.JwtUtil;
import com.example.seckill.service.AuthService;
import lombok.AllArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper sysUserMapper;
    private final StudentMapper studentMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    public LoginResponseDTO login(LoginRequestDTO loginRequestDTO) {
        SysUser sysUser = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, loginRequestDTO.getAccount()));
        if (sysUser != null) {
            if (!passwordEncoder.matches(loginRequestDTO.getPassword(), sysUser.getPassword())) {
                throw new BusinessException(ResultCode.UNAUTHORIZED, "账号或密码错误");
            }
            if (sysUser.getStatus() == 0) {
                throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用");
            }
            Long id = sysUser.getId();
            String username = sysUser.getUsername();
            String role = sysUser.getRole();
            String realName = sysUser.getRealName();
            String token = jwtUtil.generateToken(id, username, role);

            LoginResponseDTO dto = LoginResponseDTO.builder()
                    .username(username)
                    .realName(realName)
                    .token(token)
                    .role(role)
                    .build();
            return dto;
        }

        Student stu = studentMapper.selectOne(new LambdaQueryWrapper<Student>()
                .eq(Student::getStudentNo, loginRequestDTO.getAccount()));
        if (stu == null || !passwordEncoder.matches(loginRequestDTO.getPassword(), stu.getPassword())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (stu.getStatus() == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用");
        }
        Long id = stu.getId();
        String name = stu.getName();
        String stuStudentNo = stu.getStudentNo();
        String token = jwtUtil.generateToken(id, name, "STUDENT");
        LoginResponseDTO dto = LoginResponseDTO.builder()
                .token(token)
                .username(stuStudentNo)
                .role("STUDENT")
                .realName(name)
                .build();
        return dto;

    }
}
