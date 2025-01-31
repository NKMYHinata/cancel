package cn.hamm.demo.module.user;

import cn.hamm.airpower.config.Constant;
import cn.hamm.airpower.enums.ServiceError;
import cn.hamm.airpower.model.Sort;
import cn.hamm.airpower.util.PasswordUtil;
import cn.hamm.airpower.util.TreeUtil;
import cn.hamm.airpower.util.Utils;
import cn.hamm.demo.base.BaseService;
import cn.hamm.demo.common.Services;
import cn.hamm.demo.common.exception.CustomError;
import cn.hamm.demo.module.open.app.OpenAppEntity;
import cn.hamm.demo.module.system.menu.MenuEntity;
import cn.hamm.demo.module.system.permission.PermissionEntity;
import jakarta.mail.MessagingException;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * <h1>Service</h1>
 *
 * @author Hamm.cn
 */
@Service
public class UserService extends BaseService<UserEntity, UserRepository> {
    /**
     * <h2>密码盐的长度</h2>
     */
    public static final int PASSWORD_SALT_LENGTH = 4;

    /**
     * <h2>邮箱验证码key</h2>
     */
    private static final String REDIS_EMAIL_CODE_KEY = "email_code_";

    /**
     * <h2>OAUTH存储的key前缀</h2>
     */
    private static final String OAUTH_CODE_KEY = "oauth_code_";

    /**
     * <h2>COOKIE前缀</h2>
     */
    private static final String COOKIE_CODE_KEY = "cookie_code_";

    /**
     * <h2>Code缓存 包含了 Oauth2的 Code 和 验证码的 Code</h2>
     */
    private static final int CACHE_CODE_EXPIRE_SECOND = Constant.SECOND_PER_MINUTE * 5;

    /**
     * <h2>Cookie缓存</h2>
     */
    private static final int CACHE_COOKIE_EXPIRE_SECOND = Constant.SECOND_PER_DAY;

    /**
     * <h2>获取登录用户的菜单列表</h2>
     *
     * @param userId 用户id
     * @return 菜单树列表
     */
    public List<MenuEntity> getMenuListByUserId(long userId) {
        UserEntity user = get(userId);
        TreeUtil treeUtil = Utils.getTreeUtil();
        if (user.isRootUser()) {
            return treeUtil.buildTreeList(
                    Services.getMenuService().filter(new MenuEntity(), new Sort().setField("orderNo"))
            );
        }
        List<MenuEntity> menuList = new ArrayList<>();
        user.getRoleList().forEach(role -> role.getMenuList().forEach(menu -> {
            boolean isExist = menuList.stream()
                    .anyMatch(existMenu -> menu.getId().equals(existMenu.getId()));
            if (!isExist) {
                menuList.add(menu);
            }
        }));
        return treeUtil.buildTreeList(menuList);
    }

    /**
     * <h2>获取登录用户的权限列表</h2>
     *
     * @param userId 用户ID
     * @return 权限列表
     */
    public List<PermissionEntity> getPermissionListByUserId(long userId) {
        UserEntity userEntity = get(userId);
        if (userEntity.isRootUser()) {
            return Services.getPermissionService().getList(null);
        }
        List<PermissionEntity> permissionList = new ArrayList<>();
        userEntity.getRoleList().forEach(roleEntity -> roleEntity.getPermissionList().forEach(permission -> {
            boolean isExist = permissionList.stream()
                    .anyMatch(existPermission -> permission.getId().equals(existPermission.getId()));
            if (!isExist) {
                permissionList.add(permission);
            }
        }));
        return permissionList;
    }

    /**
     * <h2>修改密码</h2>
     *
     * @param user 用户信息
     */
    public void modifyUserPassword(@NotNull UserEntity user) {
        UserEntity existUser = get(user.getId());
        String code = getEmailCode(existUser.getEmail());
        ServiceError.PARAM_INVALID.whenNotEquals(code, user.getCode(), "验证码输入错误");
        String oldPassword = user.getOldPassword();
        PasswordUtil passwordUtil = Utils.getPasswordUtil();
        ServiceError.PARAM_INVALID.whenNotEqualsIgnoreCase(
                passwordUtil.encode(oldPassword, existUser.getSalt()),
                existUser.getPassword(),
                "原密码输入错误，修改密码失败"
        );
        String salt = Utils.getRandomUtil().randomString();
        user.setSalt(salt);
        user.setPassword(passwordUtil.encode(user.getPassword(), salt));
        removeEmailCodeCache(existUser.getEmail());
        update(user);
    }

    /**
     * <h2>删除指定邮箱的验证码缓存</h2>
     *
     * @param email 邮箱
     */
    public void removeEmailCodeCache(String email) {
        Utils.getRedisUtil().del(REDIS_EMAIL_CODE_KEY + email);
    }

    /**
     * <h2>重置密码</h2>
     *
     * @param userEntity 用户实体
     */
    public void resetMyPassword(@NotNull UserEntity userEntity) {
        String code = getEmailCode(userEntity.getEmail());
        ServiceError.PARAM_INVALID.whenNotEqualsIgnoreCase(code, userEntity.getCode(), "邮箱验证码不一致");
        UserEntity existUser = repository.getByEmail(userEntity.getEmail());
        ServiceError.PARAM_INVALID.whenNull(existUser, "重置密码失败，用户信息异常");
        String salt = Utils.getRandomUtil().randomString(PASSWORD_SALT_LENGTH);
        existUser.setSalt(salt);
        existUser.setPassword(Utils.getPasswordUtil().encode(userEntity.getPassword(), salt));
        removeEmailCodeCache(existUser.getEmail());
        update(existUser);
    }

    /**
     * <h2>发送邮箱验证码</h2>
     *
     * @param email 邮箱
     */
    public void sendMail(String email) throws MessagingException {
        CustomError.EMAIL_SEND_BUSY.when(hasEmailCodeInRedis(email));
        String code = Utils.getRandomUtil().randomNumbers(6);
        setCodeToRedis(email, code);
        Utils.getEmailUtil().sendCode(email, "你收到一个邮箱验证码", code, "DEMO");
    }

    /**
     * <h2>存储Oauth的一次性Code</h2>
     *
     * @param userId        用户ID
     * @param openAppEntity 保存的应用信息
     */
    public void saveOauthCode(Long userId, @NotNull OpenAppEntity openAppEntity) {
        Utils.getRedisUtil().set(getAppCodeKey(openAppEntity.getAppKey(), openAppEntity.getCode()), userId, CACHE_CODE_EXPIRE_SECOND);
    }

    /**
     * <h2>获取指定应用的OauthCode缓存Key</h2>
     *
     * @param appKey 应用Key
     * @param code   Code
     * @return 缓存的Key
     */
    protected String getAppCodeKey(String appKey, String code) {
        return OAUTH_CODE_KEY + appKey + "_" + code;
    }

    /**
     * <h2>通过AppKey和Code获取用户ID</h2>
     *
     * @param appKey AppKey
     * @param code   Code
     * @return UserId
     */
    public Long getUserIdByOauthAppKeyAndCode(String appKey, String code) {
        Object userId = Utils.getRedisUtil().get(getAppCodeKey(appKey, code));
        ServiceError.FORBIDDEN.whenNull(userId, "你的AppKey或Code错误，请重新获取");
        return Long.valueOf(userId.toString());
    }

    /**
     * <h2>删除AppOauthCode缓存</h2>
     *
     * @param appKey AppKey
     * @param code   Code
     */
    public void removeOauthCode(String appKey, String code) {
        Utils.getRedisUtil().del(getAppCodeKey(appKey, code));
    }

    /**
     * <h2>存储Cookie</h2>
     *
     * @param userId UserId
     * @param cookie Cookie
     */
    public void saveCookie(Long userId, String cookie) {
        Utils.getRedisUtil().set(COOKIE_CODE_KEY + cookie, userId, CACHE_COOKIE_EXPIRE_SECOND);
    }

    /**
     * <h2>通过Cookie获取一个用户</h2>
     *
     * @param cookie Cookie
     * @return UserId
     */
    public Long getUserIdByCookie(String cookie) {
        Object userId = Utils.getRedisUtil().get(COOKIE_CODE_KEY + cookie);
        if (Objects.isNull(userId)) {
            return null;
        }
        return Long.valueOf(userId.toString());
    }

    /**
     * <h2>ID+密码 账号+密码</h2>
     *
     * @param userEntity 用户实体
     * @return AccessToken
     */
    public String login(@NotNull UserEntity userEntity) {
        UserEntity existUser = null;
        if (Objects.nonNull(userEntity.getId())) {
            // ID登录
            existUser = getMaybeNull(userEntity.getId());
        } else if (Objects.nonNull(userEntity.getEmail())) {
            // 邮箱登录
            existUser = repository.getByEmail(userEntity.getEmail());
        } else {
            ServiceError.PARAM_INVALID.show("ID或邮箱不能为空，请确认是否传入");
        }
        CustomError.USER_LOGIN_ACCOUNT_OR_PASSWORD_INVALID.whenNull(existUser);
        // 将用户传入的密码加密与数据库存储匹配
        String encodePassword = Utils.getPasswordUtil().encode(userEntity.getPassword(), existUser.getSalt());
        CustomError.USER_LOGIN_ACCOUNT_OR_PASSWORD_INVALID.whenNotEqualsIgnoreCase(encodePassword, existUser.getPassword());
        return Utils.getSecurityUtil().createAccessToken(existUser.getId());
    }

    /**
     * <h2>邮箱验证码登录</h2>
     *
     * @param userEntity 用户实体
     * @return AccessToken
     */
    public String loginViaEmail(@NotNull UserEntity userEntity) {
        String code = getEmailCode(userEntity.getEmail());
        ServiceError.PARAM_INVALID.whenNotEquals(code, userEntity.getCode(), "邮箱验证码不正确");
        UserEntity existUser = repository.getByEmail(userEntity.getEmail());
        ServiceError.PARAM_INVALID.whenNull("邮箱或验证码不正确");
        return Utils.getSecurityUtil().createAccessToken(existUser.getId());
    }

    /**
     * <h2>用户注册</h2>
     *
     * @param userEntity 用户实体
     */
    public void register(@NotNull UserEntity userEntity) {
        // 获取发送的验证码
        String code = getEmailCode(userEntity.getEmail());
        ServiceError.PARAM_INVALID.whenNotEquals(code, userEntity.getCode(), "邮箱验证码不正确");
        // 验证邮箱是否已经注册过
        UserEntity existUser = repository.getByEmail(userEntity.getEmail());
        CustomError.USER_REGISTER_ERROR_EXIST.whenNotNull(existUser, "账号已存在,无法重复注册");
        // 获取一个随机盐
        String salt = Utils.getRandomUtil().randomString(PASSWORD_SALT_LENGTH);
        UserEntity newUser = new UserEntity();
        newUser.setEmail(userEntity.getEmail());
        newUser.setSalt(salt);
        newUser.setPassword(Utils.getPasswordUtil().encode(userEntity.getPassword(), salt));
        add(newUser);
        //删掉使用过的邮箱验证码
        removeEmailCodeCache(userEntity.getEmail());
    }

    /**
     * <h2>将验证码暂存到Redis</h2>
     *
     * @param email 邮箱
     * @param code  验证码
     */
    private void setCodeToRedis(String email, String code) {
        Utils.getRedisUtil().set(REDIS_EMAIL_CODE_KEY + email, code, CACHE_CODE_EXPIRE_SECOND);
    }

    /**
     * <h2>获取指定邮箱发送的验证码</h2>
     *
     * @param email 邮箱
     * @return 验证码
     */
    private String getEmailCode(String email) {
        Object code = Utils.getRedisUtil().get(REDIS_EMAIL_CODE_KEY + email);
        return Objects.isNull(code) ? Constant.EMPTY_STRING : code.toString();
    }

    /**
     * <h2>指定邮箱验证码是否还在缓存内</h2>
     *
     * @param email 邮箱
     * @return 是否在缓存内
     */
    private boolean hasEmailCodeInRedis(String email) {
        return Utils.getRedisUtil().hasKey(REDIS_EMAIL_CODE_KEY + email);
    }

    @Override
    protected void beforeDelete(long id) {
        UserEntity entity = get(id);
        ServiceError.FORBIDDEN_DELETE.when(entity.isRootUser(), "系统内置用户无法被删除!");
    }

    @Override
    protected @NotNull UserEntity beforeAdd(@NotNull UserEntity user) {
        UserEntity existUser = repository.getByEmail(user.getEmail());
        ServiceError.FORBIDDEN_EXIST.whenNotNull(existUser, "邮箱已经存在，请勿重复添加用户");
        if (!StringUtils.hasLength(user.getPassword())) {
            // 创建时没有设置密码的话 随机一个密码
            String salt = Utils.getRandomUtil().randomString(PASSWORD_SALT_LENGTH);
            user.setPassword(Utils.getPasswordUtil().encode("Aa123456", salt));
            user.setSalt(salt);
        }
        return user;
    }
}
