package cn.hamm.demo.module.system.permission;

import cn.hamm.airpower.enums.ServiceError;
import cn.hamm.airpower.util.Utils;
import cn.hamm.demo.Application;
import cn.hamm.demo.base.BaseService;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * <h1>Service</h1>
 *
 * @author Hamm.cn
 */
@Service
@Slf4j
public class PermissionService extends BaseService<PermissionEntity, PermissionRepository> {
    /**
     * <h2>通过标识获取一个权限</h2>
     *
     * @param identity 权限标识
     * @return 权限
     */
    public PermissionEntity getPermissionByIdentity(String identity) {
        return repository.getByIdentity(identity);
    }

    @Override
    protected void beforeDelete(long id) {
        PermissionEntity entity = get(id);
        ServiceError.FORBIDDEN_DELETE.when(entity.getIsSystem(), "系统内置权限无法被删除!");
        List<PermissionEntity> children = filter(new PermissionEntity().setParentId(id));
        ServiceError.FORBIDDEN_DELETE.when(!children.isEmpty(), "含有子权限,无法删除!");
    }

    @Override
    protected @NotNull List<PermissionEntity> afterGetList(@NotNull List<PermissionEntity> list) {
        for (PermissionEntity item : list) {
            item.excludeBaseData();
        }
        return list;
    }

    /**
     * <h2>加载权限</h2>
     */
    public final void loadPermission() {
        List<PermissionEntity> permissions = Utils.getAccessUtil().scanPermission(Application.class, PermissionEntity.class);
        for (PermissionEntity permission : permissions) {
            PermissionEntity exist = getPermissionByIdentity(permission.getIdentity());
            if (Objects.isNull(exist)) {
                exist = new PermissionEntity()
                        .setName(permission.getName())
                        .setIdentity(permission.getIdentity())
                        .setIsSystem(true);
                exist.setId(add(exist));
            } else {
                exist.setName(permission.getName())
                        .setIdentity(permission.getIdentity())
                        .setIsSystem(true);
                update(exist);
            }
            exist = get(exist.getId());
            for (PermissionEntity subPermission : permission.getChildren()) {
                PermissionEntity existSub = getPermissionByIdentity(subPermission.getIdentity());
                if (Objects.isNull(existSub)) {
                    existSub = new PermissionEntity()
                            .setName(subPermission.getName())
                            .setIdentity(subPermission.getIdentity())
                            .setIsSystem(true)
                            .setParentId(exist.getId());
                    add(existSub);
                } else {
                    existSub.setName(subPermission.getName())
                            .setIdentity(subPermission.getIdentity())
                            .setIsSystem(true)
                            .setParentId(exist.getId());
                    update(existSub);
                }
            }
        }
    }
}
