package com.lumensteward.clawbot.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.PetProfileEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.PetProfileMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * {@link PetProfileRepository} 的 MyBatis-Plus 实现。
 *
 * <p>注意：{@code live_marker} 为生成列，实体未声明，所有查询由 {@code TableLogic}
 * 自动附加 {@code deleted_at IS NULL}（存活过滤）。
 */
@Repository
public class PetProfileRepositoryImpl implements PetProfileRepository {

    private final PetProfileMapper petProfileMapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param petProfileMapper 档案 Mapper
     */
    public PetProfileRepositoryImpl(PetProfileMapper petProfileMapper) {
        this.petProfileMapper = petProfileMapper;
    }

    @Override
    public int insert(PetProfileEntity entity) {
        return petProfileMapper.insert(entity);
    }

    @Override
    public List<PetProfileEntity> listLive(String openid) {
        return petProfileMapper.selectList(new LambdaQueryWrapper<PetProfileEntity>()
                .eq(PetProfileEntity::getOpenid, openid)
                .orderByAsc(PetProfileEntity::getCreatedAt));
    }

    @Override
    public Optional<PetProfileEntity> findLiveByName(String openid, String petName) {
        PetProfileEntity entity = petProfileMapper.selectOne(new LambdaQueryWrapper<PetProfileEntity>()
                .eq(PetProfileEntity::getOpenid, openid)
                .eq(PetProfileEntity::getPetName, petName)
                .last("limit 1"));
        return Optional.ofNullable(entity);
    }

    @Override
    public int updateById(PetProfileEntity entity) {
        return petProfileMapper.updateById(entity);
    }

    @Override
    public int softDeleteById(Long id) {
        // 依实体 @TableLogic：deleteById 转换为 UPDATE ... SET deleted_at = now()
        return petProfileMapper.deleteById(id);
    }

    @Override
    public boolean existsLive(String openid, String petName) {
        Long count = petProfileMapper.selectCount(new LambdaQueryWrapper<PetProfileEntity>()
                .eq(PetProfileEntity::getOpenid, openid)
                .eq(PetProfileEntity::getPetName, petName));
        return count != null && count > 0;
    }
}
