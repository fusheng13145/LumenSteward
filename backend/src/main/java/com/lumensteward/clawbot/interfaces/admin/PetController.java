package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.admin.UserQueryService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.domain.model.PetProfileCommand;
import com.lumensteward.clawbot.domain.model.PetProfilePatch;
import com.lumensteward.clawbot.domain.model.PetProfileView;
import com.lumensteward.clawbot.domain.service.PetProfileService;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxUserEntity;
import com.lumensteward.clawbot.interfaces.assembler.MaskingAssembler;
import com.lumensteward.clawbot.interfaces.dto.pet.PetCreateRequest;
import com.lumensteward.clawbot.interfaces.dto.pet.PetUpdateRequest;
import com.lumensteward.clawbot.interfaces.dto.pet.PetVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 宠物档案（管理侧）控制器（架构 4.3 / SRS FR-14）。
 *
 * <p>读接口对全部角色开放；写接口（增/改/删）为 OPERATOR+（SUPER_ADMIN/OPERATOR）。
 * 复用领域服务 {@link PetProfileService}，从而与对话侧真实工具共享唯一约束双保险与软删除语义。
 */
@RestController
@RequestMapping("/api")
@Tag(name = "宠物档案（管理侧）", description = "查询 / 新增 / 更新 / 软删除（OPERATOR+ 写）")
public class PetController {

    private final PetProfileService petProfileService;
    private final UserQueryService userQueryService;
    private final MaskingAssembler maskingAssembler;

    /**
     * 构造器注入（G-14）。
     *
     * @param petProfileService 档案领域服务
     * @param userQueryService  用户查询服务（由用户主键解析 openid）
     * @param maskingAssembler  脱敏装配器
     */
    public PetController(PetProfileService petProfileService,
                         UserQueryService userQueryService,
                         MaskingAssembler maskingAssembler) {
        this.petProfileService = petProfileService;
        this.userQueryService = userQueryService;
        this.maskingAssembler = maskingAssembler;
    }

    /**
     * 查询用户全部存活宠物。
     *
     * @param id 用户主键
     * @return 档案列表
     */
    @GetMapping("/users/{id}/pets")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "查询用户宠物")
    public ApiResponse<List<PetVO>> listByUser(@PathVariable Long id) {
        WxUserEntity user = userQueryService.requireById(id);
        List<PetProfileView> pets = petProfileService.listLive(user.getOpenid());
        return ApiResponse.success(maskingAssembler.mapList(pets, maskingAssembler::toPetVO));
    }

    /**
     * 新增档案（OPERATOR+）。
     *
     * @param id      用户主键
     * @param request 创建请求
     * @return 新建档案
     */
    @PostMapping("/users/{id}/pets")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR')")
    @Operation(summary = "新增档案", description = "昵称唯一（双保险），生日不得晚于今日")
    public ApiResponse<PetVO> create(@PathVariable Long id,
                                     @Valid @RequestBody PetCreateRequest request) {
        WxUserEntity user = userQueryService.requireById(id);
        PetProfileCommand command = new PetProfileCommand(request.petName(), request.petType(),
                request.breed(), request.gender(), request.birthday(), request.weightKg(),
                request.personality(), request.notes());
        PetProfileView created = petProfileService.create(user.getOpenid(), command);
        return ApiResponse.success(maskingAssembler.toPetVO(created));
    }

    /**
     * 更新档案（OPERATOR+，仅更新非空项）。
     *
     * @param id      档案主键
     * @param request 更新请求
     * @return 更新后档案
     */
    @PutMapping("/pets/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR')")
    @Operation(summary = "更新档案", description = "增量更新，前后值写入 log_audit")
    public ApiResponse<PetVO> update(@PathVariable Long id,
                                     @Valid @RequestBody PetUpdateRequest request) {
        PetProfilePatch patch = new PetProfilePatch(request.petType(), request.breed(),
                request.gender(), request.birthday(), request.weightKg(), request.personality(),
                request.notes());
        PetProfileView updated = petProfileService.updateById(id, patch);
        return ApiResponse.success(maskingAssembler.toPetVO(updated));
    }

    /**
     * 软删除档案（OPERATOR+）。
     *
     * @param id 档案主键
     * @return 空响应
     */
    @DeleteMapping("/pets/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR')")
    @Operation(summary = "软删除档案", description = "设置 deleted_at；同名可重建（AC-C6）")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        petProfileService.softDeleteById(id);
        return ApiResponse.success();
    }
}
