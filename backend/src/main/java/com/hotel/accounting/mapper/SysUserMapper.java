package com.hotel.accounting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hotel.accounting.model.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("select * from sys_user where username = #{username}")
    SysUser selectOneByUsername(@Param("username") String username);
}
