package com.nexcompute.management.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.config.NexcomputeProperties;
import com.nexcompute.management.domain.*;
import com.nexcompute.management.filetransfer.ImageTransferEvent;
import com.nexcompute.management.registry.RegistryClient;
import com.nexcompute.management.repository.*;
import com.nexcompute.management.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * 镜像管理服务（任务 9.4、9.5、9.7）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {

    /** 镜像可见性（platform-refinements） */
    public static final String VIS_PRIVATE = "PRIVATE";           // 仅本人+管理员（commit/用户上传默认）
    public static final String VIS_SHARED_TO_ALL = "SHARED_TO_ALL"; // 全用户可见（管理员上传/公共镜像库）
    public static final String VIS_SHARED = "SHARED";            // 已显式共享给个别用户/课题组

    /** 镜像分发方式（registry-image-distribution V35）：TAR=管理端 tar 分发（存量）；REGISTRY=私有仓库 pull 分发。 */
    public static final String DISTRIBUTION_TAR = "TAR";
    public static final String DISTRIBUTION_REGISTRY = "REGISTRY";

    private final ImageMetadataRepository imageRepository;
    private final ImageShareRepository shareRepository;
    private final UserRepository userRepository;
    private final NexcomputeProperties properties;
    private final ObjectMapper objectMapper;
    private final EmailService emailService;
    private final RegistryClient registryClient;

    /** 暴露配置供 Controller 使用（tar 存储路径） */
    public NexcomputeProperties getProperties() {
        return properties;
    }

    /**
     * 列出可见镜像（任务 9.4，platform-refinements 6.1/6.2 调整可见性规则）
     * - 所有用户：公共镜像（isPublic=true，自动同步）+ 管理员上传的全用户可见镜像（SHARED_TO_ALL，不自动同步）
     * - 学生/导师：自有 + 被显式共享 + 公共 + SHARED_TO_ALL
     * - 管理员：全部
     * 注：导师不再默认可见课题组学生的私有/commit 镜像（6.1），需学生显式共享。
     */
    public List<ImageMetadata> listVisible() {
        UserRole role = SecurityUtils.getCurrentRole();
        Long userId = SecurityUtils.getCurrentUserId();

        // 按 id 去重（platform-refinements：避免同一镜像因同时命中多个查询条件而重复展示）
        Map<Long, ImageMetadata> byId = new LinkedHashMap<>();
        // 公共镜像（isPublic=true，走公共镜像库自动同步）
        imageRepository.findByIsPublicTrue().forEach(i -> byId.put(i.getId(), i));
        // 管理员上传的全用户可见镜像（SHARED_TO_ALL，不自动同步，按需 docker load）
        imageRepository.findByVisibility(VIS_SHARED_TO_ALL).forEach(i -> byId.put(i.getId(), i));

        if (role == UserRole.ADMIN) {
            imageRepository.findAll().forEach(i -> byId.put(i.getId(), i));
        } else {
            // 自有
            imageRepository.findByOwnerId(userId).forEach(i -> byId.put(i.getId(), i));
            // 被显式共享
            shareRepository.findBySharedToUserId(userId).stream()
                    .map(s -> imageRepository.findById(s.getImageId()).orElse(null))
                    .filter(Objects::nonNull)
                    .forEach(i -> byId.put(i.getId(), i));
            // 注：导师不再默认可见课题组学生的私有/commit 镜像，需学生显式共享（platform-refinements 6.1）
        }
        return new ArrayList<>(byId.values());
    }

    public ImageMetadata getImage(Long id) {
        return imageRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.IMAGE_NOT_FOUND));
    }

    /**
     * 按 imageRef（name:tag）解析当前用户可见的镜像（platform-improvements 任务 2.3）。
     * 容器创建时 imageRef 必须命中管理端已配置镜像，拒绝自由文本。
     * 返回匹配的镜像元数据（含 tarPath/appPorts 供分发与端口预填）。
     * platform-refinements 6.4：仅 READY 镜像可用于创建容器（回传未完成不可用）。
     * registry-image-distribution 3.3：REGISTRY 镜像额外要求 registry_valid=true
     * （未推送/已从仓库移除的无效镜像不可选）；TAR 镜像维持仅 READY。
     */
    public ImageMetadata resolveVisibleImage(String imageRef) {
        if (imageRef == null || imageRef.isBlank()) {
            throw new BusinessException(ErrorCode.IMAGE_REF_NOT_ALLOWED, "镜像不能为空");
        }
        return listVisible().stream()
                .filter(img -> imageRef.equals(img.getRef()))
                .filter(img -> "READY".equals(img.getStatus()))
                .filter(img -> !DISTRIBUTION_REGISTRY.equals(img.getDistribution())
                        || Boolean.TRUE.equals(img.getRegistryValid()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.IMAGE_REF_NOT_ALLOWED,
                        "镜像不在可选范围或未就绪：" + imageRef));
    }

    /**
     * 镜像元数据注册（容器 commit 后由受控端上传 tar 完成时调用，任务 9.4）
     * platform-refinements 6.1：commit 镜像默认 visibility=PRIVATE（仅本人+管理员）。
     */
    @Transactional
    public ImageMetadata registerImage(String name, String tag, Long ownerId,
                                       String sourceContainer, Long sizeBytes, String checksum) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        ImageMetadata image = ImageMetadata.builder()
                .name(name)
                .tag(tag != null ? tag : "latest")
                .ownerId(ownerId)
                .ownerName(owner.getRealName())
                .groupId(owner.getGroupId())
                .sizeBytes(sizeBytes)
                .tarPath(properties.getStorage().getImageTarDir() + "/" + ownerId + "/" + name + "-" + tag + ".tar")
                .isPublic(false)
                .sourceContainer(sourceContainer)
                .checksum(checksum)
                .status("READY")
                .visibility(VIS_PRIVATE)
                .build();
        return imageRepository.save(image);
    }

    /**
     * 容器 commit 镜像元数据注册（platform-refinements 6.3）。
     * 含 project/note/sourceWorkerId/usageInstructions，状态先为 UPLOADING，持久化确认后置 READY。
     * registry-image-distribution D4：commit 镜像改推仓库，distribution=REGISTRY 时
     * name=完整 repo 名（工号-项目-镜像名-标签-备注-随机串）、tag=latest、不落 tarPath；
     * 命令成功由 {@link #markRegistryImagePushed} 置 READY+registry_valid=true。
     */
    @Transactional
    public ImageMetadata registerCommitImage(String name, String tag, Long ownerId, String sourceContainer,
                                             String project, String note, String sourceWorkerId,
                                             Long sizeBytes, String checksum, String tarPath, String mountPoint,
                                             String distribution) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        boolean registry = DISTRIBUTION_REGISTRY.equals(distribution);
        ImageMetadata image = ImageMetadata.builder()
                .name(name)
                .tag(tag != null ? tag : "latest")
                .ownerId(ownerId)
                .ownerName(owner.getRealName())
                .groupId(owner.getGroupId())
                .sizeBytes(sizeBytes)
                .tarPath(registry ? null : (tarPath != null ? tarPath
                        : properties.getStorage().getImageTarDir() + "/" + ownerId + "/" + name + "-" + tag + ".tar"))
                .isPublic(false)
                .sourceContainer(sourceContainer)
                .project(project)
                .note(note)
                .sourceWorkerId(sourceWorkerId)
                .mountPoint(mountPoint)
                .checksum(checksum)
                .status("UPLOADING")
                .distribution(registry ? DISTRIBUTION_REGISTRY : DISTRIBUTION_TAR)
                .visibility(VIS_PRIVATE)
                .build();
        return imageRepository.save(image);
    }

    /** commit push 确认成功（D4）：READY + registry_valid=true（受控端已确认 push 全部完成）。 */
    @Transactional
    public ImageMetadata markRegistryImagePushed(Long imageId) {
        ImageMetadata image = getImage(imageId);
        image.setStatus("READY");
        image.setRegistryValid(true);
        image.setRegistryCheckedAt(Instant.now());
        ImageMetadata saved = imageRepository.save(image);
        log.info("[Image] commit 镜像已推送仓库: {}:{}", image.getName(), image.getTag());
        return saved;
    }

    /** 更新镜像大小（D4：commit push 成功后受控端回传 sizeBytes 落盘）。 */
    @Transactional
    public ImageMetadata updateSizeBytes(Long imageId, Long sizeBytes) {
        ImageMetadata image = getImage(imageId);
        image.setSizeBytes(sizeBytes);
        return imageRepository.save(image);
    }

    // ===== registry-image-distribution：私有仓库镜像登记 / 有效性 / 推送命令 / 无标记镜像 =====

    /**
     * 登记私有仓库镜像（无文件，D3）：原始镜像名:原始标签，status=UPLOADING、distribution=REGISTRY。
     * 用户经操作列"上传"弹窗的 tag/push 命令自行推送，推送后"刷新状态"确认有效。
     * 重名校验：任何既有记录 name:tag 相同即拒绝（含 TAR 类，避免 resolveVisibleImage 引用歧义）。
     */
    @Audited(action = "IMAGE_REGISTER", targetType = "IMAGE", targetIdExpr = "#result.id")
    @Transactional
    public ImageMetadata registerRegistryImage(String name, String tag, List<Integer> appPorts,
                                               String mountPoint, String usageInstructions) {
        Long userId = SecurityUtils.getCurrentUserId();
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        String finalName = name == null ? "" : name.trim();
        String finalTag = (tag == null || tag.isBlank()) ? "latest" : tag.trim();
        if (finalName.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "原始镜像名不能为空");
        }
        // docker repo 名规则：小写字母/数字/./_/-/，可含 / 分段；大写会在 docker tag 时被拒
        if (!finalName.matches("[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*(/[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*)*")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "原始镜像名仅允许小写字母/数字/./_/-/ 与路径分段");
        }
        if (!finalTag.matches("[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "原始标签格式非法（字母数字开头，可含 ._-）");
        }
        if (imageRepository.existsByNameAndTag(finalName, finalTag)) {
            throw new BusinessException(ErrorCode.IMAGE_ALREADY_EXISTS,
                    "已存在同名镜像记录：" + finalName + ":" + finalTag);
        }

        ImageMetadata image = ImageMetadata.builder()
                .name(finalName)
                .tag(finalTag)
                .ownerId(userId)
                .ownerName(owner.getRealName())
                .groupId(owner.getGroupId())
                .isPublic(false)
                .sourceContainer("registry-register")
                .status("UPLOADING")
                .distribution(DISTRIBUTION_REGISTRY)
                .appPorts(dedupePorts(appPorts))
                .usageInstructions(usageInstructions)
                .mountPoint(normalizeMountPoint(mountPoint))
                .visibility(owner.getRole() == UserRole.ADMIN ? VIS_SHARED_TO_ALL : VIS_PRIVATE)
                .build();
        ImageMetadata saved = imageRepository.save(image);
        log.info("[Image] 仓库镜像登记: {}:{} (待用户推送)", finalName, finalTag);
        return saved;
    }

    /**
     * 有效性刷新（D3）：经 Registry v2 API HEAD manifest 检查是否已推送。
     * exists=true 且原 UPLOADING -> READY；false 仅更新结论不动状态（UPLOADING/READY 均可能，均不可用于创建容器）。
     * 仓库不可达：RegistryClient 抛 BusinessException，本方法不捕获 -> 不改变原结论，不误标无效。
     */
    @Transactional
    public ImageMetadata refreshValidity(Long imageId) {
        ImageMetadata image = getImage(imageId);
        boolean exists = registryClient.exists(image.getName(), image.getTag());
        image.setRegistryValid(exists);
        image.setRegistryCheckedAt(Instant.now());
        if (exists && "UPLOADING".equals(image.getStatus())) {
            image.setStatus("READY");
        }
        ImageMetadata saved = imageRepository.save(image);
        log.info("[Image] 仓库有效性刷新: {}:{} -> {}", image.getName(), image.getTag(), exists);
        return saved;
    }

    /** 推送命令（操作列"上传"弹窗数据，D3）：命令由后端拼装，前端不硬编码仓库地址。 */
    public PushCommands getPushCommands(Long imageId) {
        ImageMetadata image = getImage(imageId);
        String src = image.getName() + ":" + image.getTag();
        String dst = registryClient.getRegistryUrl() + "/" + src;
        return new PushCommands(registryClient.getRegistryUrl(),
                "docker tag " + src + " " + dst,
                "docker push " + dst);
    }

    public record PushCommands(String registryUrl, String tagCmd, String pushCmd) {}

    /**
     * 无标记镜像（D3，仅管理员）：仓库中存在（catalog×tags）但系统内无 name:tag 记录的镜像。
     */
    public List<UntaggedImage> listUntaggedRegistryImages() {
        requireAdmin();
        Set<String> known = imageRepository.findAll().stream()
                .map(ImageMetadata::getRef)
                .collect(java.util.stream.Collectors.toSet());
        List<UntaggedImage> result = new ArrayList<>();
        for (String repo : registryClient.catalog()) {
            List<String> untagged = registryClient.tags(repo).stream()
                    .filter(t -> !known.contains(repo + ":" + t))
                    .toList();
            if (!untagged.isEmpty()) {
                result.add(new UntaggedImage(repo, untagged));
            }
        }
        return result;
    }

    public record UntaggedImage(String repo, List<String> tags) {}

    /**
     * 无标记镜像补录（D3，仅管理员）：以仓库 repo 名为镜像名建记录，READY + registry_valid=true，
     * sourceContainer=registry-claim，visibility 默认 SHARED_TO_ALL。
     */
    @Audited(action = "IMAGE_CLAIM_REGISTRY", targetType = "IMAGE", targetIdExpr = "#result.id")
    @Transactional
    public ImageMetadata claimUntaggedRegistryImage(String repo, String tag, List<Integer> appPorts,
                                                    String mountPoint, String usageInstructions, String visibility) {
        requireAdmin();
        if (repo == null || repo.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "镜像名(repo)不能为空");
        }
        String finalTag = (tag == null || tag.isBlank()) ? "latest" : tag.trim();
        String finalName = repo.trim();
        if (imageRepository.existsByNameAndTag(finalName, finalTag)) {
            throw new BusinessException(ErrorCode.IMAGE_ALREADY_EXISTS,
                    "已存在同名镜像记录：" + finalName + ":" + finalTag);
        }
        String finalVisibility = (visibility == null || visibility.isBlank())
                ? VIS_SHARED_TO_ALL : visibility;
        if (!VIS_SHARED_TO_ALL.equals(finalVisibility) && !VIS_PRIVATE.equals(finalVisibility)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的可见性: " + finalVisibility);
        }
        Long userId = SecurityUtils.getCurrentUserId();
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        ImageMetadata image = ImageMetadata.builder()
                .name(finalName)
                .tag(finalTag)
                .ownerId(userId)
                .ownerName(owner.getRealName())
                .groupId(owner.getGroupId())
                .isPublic(false)
                .sourceContainer("registry-claim")
                .status("READY")
                .distribution(DISTRIBUTION_REGISTRY)
                .registryValid(true)
                .registryCheckedAt(Instant.now())
                .appPorts(dedupePorts(appPorts))
                .usageInstructions(usageInstructions)
                .mountPoint(normalizeMountPoint(mountPoint))
                .visibility(finalVisibility)
                .build();
        ImageMetadata saved = imageRepository.save(image);
        log.info("[Image] 无标记镜像补录: {}:{} visibility={}", finalName, finalTag, finalVisibility);
        return saved;
    }

    /** 仅管理员可操作（无标记镜像相关）。 */
    private void requireAdmin() {
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅管理员可操作");
        }
    }

    /** 应用端口校验 + 按首现顺序去重（登记/补录复用 editMetadata 语义）。 */
    private List<Integer> dedupePorts(List<Integer> appPorts) {
        validatePorts(appPorts);
        if (appPorts == null || appPorts.isEmpty()) return null;
        return new ArrayList<>(new LinkedHashSet<>(appPorts));
    }

    /**
     * 共享镜像给其他用户（任务 9.5）
     * platform-refinements 6.6：共享后置 visibility=SHARED。
     */
    @Audited(action = "IMAGE_SHARE", targetType = "IMAGE", targetIdExpr = "#imageId")
    @Transactional
    public void shareImage(Long imageId, Long targetUserId) {
        checkOwnership(imageId);
        boolean isNew = !shareRepository.existsByImageIdAndSharedToUserId(imageId, targetUserId);
        if (isNew) {
            shareRepository.save(ImageShare.builder()
                    .imageId(imageId)
                    .sharedToUserId(targetUserId)
                    .build());
        }
        markShared(imageId);
        // email-notification 5.6：新共享成功后通知被共享个人 + 原可见用户（去重）
        if (isNew) {
            String targetName = userRepository.findById(targetUserId).map(User::getRealName).orElse("用户");
            sendImagePermissionEmails(imageId, Set.of(targetUserId), "共享给 " + targetName);
        }
    }

    /** 按工号精准共享（platform-refinements 6.6）：工号不匹配则拒绝并提示无此用户。 */
    @Audited(action = "IMAGE_SHARE", targetType = "IMAGE", targetIdExpr = "#imageId")
    @Transactional
    public void shareImageByWorkerId(Long imageId, String targetWorkerId) {
        checkOwnership(imageId);
        User target = userRepository.findByStudentId(targetWorkerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "无此用户（工号不匹配）"));
        shareImage(imageId, target.getId());
    }

    /** 共享给课题组全体（platform-refinements 6.6）：课题组所有成员可见。 */
    @Audited(action = "IMAGE_SHARE", targetType = "IMAGE", targetIdExpr = "#imageId")
    @Transactional
    public void shareImageToGroup(Long imageId, Long targetGroupId) {
        checkOwnership(imageId);
        List<User> members = userRepository.findByGroupId(targetGroupId);
        Set<Long> newlyShared = new LinkedHashSet<>();
        for (User m : members) {
            if (!shareRepository.existsByImageIdAndSharedToUserId(imageId, m.getId())) {
                shareRepository.save(ImageShare.builder()
                        .imageId(imageId)
                        .sharedToUserId(m.getId())
                        .build());
                newlyShared.add(m.getId());
            }
        }
        markShared(imageId);
        // email-notification 5.6：新共享成员 + 原可见用户（去重）
        if (!newlyShared.isEmpty()) {
            sendImagePermissionEmails(imageId, newlyShared, "共享给课题组（新增 " + newlyShared.size() + " 位）");
        }
    }

    /** 共享后将 PRIVATE 镜像置为 SHARED（SHARED_TO_ALL 不变）。 */
    private void markShared(Long imageId) {
        ImageMetadata image = getImage(imageId);
        if (VIS_PRIVATE.equals(image.getVisibility())) {
            image.setVisibility(VIS_SHARED);
            imageRepository.save(image);
        }
    }

    /**
     * 设置镜像可见性（platform-refinements：权限弹窗"全员"选项）。
     * SHARED_TO_ALL = 全用户可见；PRIVATE = 仅本人+管理员。仅所有者或管理员可设置。
     */
    @Audited(action = "IMAGE_SET_VISIBILITY", targetType = "IMAGE", targetIdExpr = "#imageId")
    @Transactional
    public void setVisibility(Long imageId, String visibility) {
        checkOwnership(imageId);
        if (!VIS_SHARED_TO_ALL.equals(visibility) && !VIS_PRIVATE.equals(visibility)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的可见性: " + visibility);
        }
        ImageMetadata image = getImage(imageId);
        image.setVisibility(visibility);
        imageRepository.save(image);
        log.info("[Image] 可见性已设置: {}:{} -> {}", image.getName(), image.getTag(), visibility);
        // email-notification 5.6：可见性变更通知原可见用户（无新增个人，relation 均为 ALREADY_VISIBLE）
        String label = VIS_SHARED_TO_ALL.equals(visibility) ? "全员可见" : "仅本人";
        sendImagePermissionEmails(imageId, Set.of(), "可见性改为 " + label);
    }

    /**
     * 编辑镜像应用端口、使用说明与容器内挂载点（仅所有者/管理员）。
     */
    @Audited(action = "IMAGE_EDIT_META", targetType = "IMAGE", targetIdExpr = "#imageId")
    @Transactional
    public ImageMetadata editMetadata(Long imageId, List<Integer> appPorts, String usageInstructions, String mountPoint) {
        checkOwnership(imageId);
        // 应用端口校验 + 去重（保留首次出现顺序）
        validatePorts(appPorts);
        List<Integer> deduped = null;
        if (appPorts != null && !appPorts.isEmpty()) {
            java.util.LinkedHashSet<Integer> seen = new java.util.LinkedHashSet<>(appPorts);
            deduped = new java.util.ArrayList<>(seen);
        }
        ImageMetadata image = getImage(imageId);
        image.setAppPorts(deduped);
        image.setUsageInstructions(usageInstructions);
        image.setMountPoint(normalizeMountPoint(mountPoint));
        ImageMetadata saved = imageRepository.save(image);
        log.info("[Image] 元数据已更新: {}:{} appPorts={} usage={} mountPoint={}", image.getName(), image.getTag(), deduped,
                usageInstructions == null ? "(空)" : (usageInstructions.length() + "字"),
                image.getMountPoint() == null ? "(空)" : image.getMountPoint());
        return saved;
    }

    /** 容器内挂载点规范化：去空白；空字符串统一存 null（避免 NOT NULL 默认值误导）。 */
    private String normalizeMountPoint(String mountPoint) {
        if (mountPoint == null) return null;
        String mp = mountPoint.trim();
        return mp.isEmpty() ? null : mp;
    }

    /**
     * 应用端口校验：每个端口须为 1-65535 的整数，否则抛 BusinessException。
     */
    public static void validatePorts(List<Integer> ports) {
        if (ports == null) return;
        for (Integer p : ports) {
            if (p == null || p < 1 || p > 65535) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "应用端口须为 1-65535 的整数，无效端口: " + p);
            }
        }
    }

    // 公共镜像库上传已下线（platform-refinements）：改用普通上传 + "全员可见"权限。
    // 存量公共镜像（is_public=true）仍经 /agent/images/public/list 同步，listPublicImages 保留。


    @Audited(action = "IMAGE_DELETE", targetType = "IMAGE", targetIdExpr = "#id")
    @Transactional
    public void deleteImage(Long id) {
        ImageMetadata image = getImage(id);
        // 删除 tar 文件
        if (image.getTarPath() != null) {
            try {
                Files.deleteIfExists(Paths.get(image.getTarPath()));
            } catch (IOException e) {
                log.warn("[Image] 删除 tar 文件失败: {}", e.getMessage());
            }
        }
        imageRepository.delete(image);
    }

    /**
     * 用户直接上传 tar 文件作为镜像（任务 3）
     * tar 存储于管理端，记录元数据归属用户。
     * platform-improvements 任务 3.2：解析 tar manifest.json -> 镜像 config ->
     * RepoTags/ExposedPorts，据此预填 name/tag 并存 app_ports。
     * platform-refinements 4.2：增加 usageInstructions；
     * 6.2：管理员上传置 visibility=SHARED_TO_ALL（全用户可见），其他用户上传默认 PRIVATE。
     */
    @Audited(action = "IMAGE_UPLOAD_TAR", targetType = "IMAGE", targetIdExpr = "#result.id")
    @Transactional
    public ImageMetadata uploadTarImage(String name, String tag, Long ownerId,
                                       String tarPath, Long sizeBytes, String checksum,
                                       List<Integer> appPortsOverride, String usageInstructions, String mountPoint) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // 解析 tar：预填 name/tag 与 app_ports（任务 3.2）
        TarMetadata parsed = parseTarMetadata(Paths.get(tarPath));
        String finalName = (name != null && !name.isBlank()) ? name : parsed.name();
        String finalTag = (tag != null && !tag.isBlank()) ? tag : (parsed.tag() != null ? parsed.tag() : "latest");
        if (finalName == null || finalName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无法从 tar 解析镜像名，请手动填写镜像名");
        }
        List<Integer> finalAppPorts = (appPortsOverride != null && !appPortsOverride.isEmpty())
                ? appPortsOverride : parsed.appPorts();

        ImageMetadata image = ImageMetadata.builder()
                .name(finalName)
                .tag(finalTag)
                .ownerId(ownerId)
                .ownerName(owner.getRealName())
                .groupId(owner.getGroupId())
                .sizeBytes(sizeBytes)
                .tarPath(tarPath)
                .isPublic(false)
                .sourceContainer("tar-upload")
                .checksum(checksum)
                .status("READY")
                .appPorts(finalAppPorts)
                .usageInstructions(usageInstructions)
                .mountPoint(normalizeMountPoint(mountPoint))
                .visibility(owner.getRole() == UserRole.ADMIN ? VIS_SHARED_TO_ALL : VIS_PRIVATE)
                .build();
        return imageRepository.save(image);
    }

    /** 受控端上传/构建完成后更新镜像状态（任务 3） */
    @Transactional
    public void markImageReady(Long imageId, Long sizeBytes, String checksum) {
        ImageMetadata image = getImage(imageId);
        image.setSizeBytes(sizeBytes);
        image.setChecksum(checksum);
        image.setStatus("READY");
        imageRepository.save(image);
        log.info("[Image] 镜像就绪: {}:{}", image.getName(), image.getTag());
    }

    /** commit 镜像回传失败置 FAILED（platform-refinements 6.4） */
    @Transactional
    public void markImageFailed(Long imageId) {
        ImageMetadata image = getImage(imageId);
        image.setStatus("FAILED");
        imageRepository.save(image);
        log.warn("[Image] 镜像回传失败: {}:{}", image.getName(), image.getTag());
    }

    /**
     * 受控端 commit 镜像 tar 上传完成/失败回调（platform-refinements 6.4）。
     * transferId 格式 commit-{imageId}-{uuid}，解析 imageId 后置 READY/FAILED。
     * 回传未完成（UPLOADING）的镜像不可用于创建容器（resolveVisibleImage 过滤非 READY）。
     */
    @EventListener
    @Transactional
    public void onImageTransfer(ImageTransferEvent event) {
        if (!"image".equals(event.type())) return;
        Long imageId = parseCommitImageId(event.transferId());
        if (imageId == null) return;
        // platform-refinements #3b：防御性，任何异常不得回传给 file-transfer 的上传响应（否则完成块 404/500）
        try {
            if (event.success()) {
                markImageReady(imageId, event.totalBytes(), event.checksum());
                ImageMetadata image = imageRepository.findById(imageId).orElse(null);
                if (image != null) {
                    image.setTarPath(event.targetPath());
                    imageRepository.save(image);
                } else {
                    log.warn("[Image] 完成回调：镜像 {} 未找到（可能未提交或已删除）", imageId);
                }
            } else {
                markImageFailed(imageId);
            }
        } catch (Exception e) {
            log.error("[Image] 完成回调处理失败（不影响上传）: imageId={} err={}", imageId, e.getMessage());
        }
    }

    /** 解析 commit-{imageId}-{uuid} 中的 imageId，非 commit 传输返回 null。 */
    private Long parseCommitImageId(String transferId) {
        if (transferId == null || !transferId.startsWith("commit-")) return null;
        String[] parts = transferId.split("-", 3); // [commit, imageId, uuid]
        if (parts.length < 2) return null;
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 下载镜像 tar（platform-refinements #2）：流式返回管理端存储的 tar */
    public void downloadTar(Long imageId, java.io.OutputStream out) {
        ImageMetadata image = getImage(imageId);
        if (image.getTarPath() == null || image.getTarPath().isBlank()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "镜像 tar 路径不存在");
        }
        java.nio.file.Path p = java.nio.file.Paths.get(image.getTarPath());
        if (!java.nio.file.Files.exists(p)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "镜像 tar 文件不存在");
        }
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(p)) {
            in.transferTo(out);
        } catch (java.io.IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载失败: " + e.getMessage());
        }
    }

    /** 获取公共镜像列表（受控端同步用） */
    public List<ImageMetadata> listPublicImages() {
        return imageRepository.findByIsPublicTrue();
    }

    // ===== email-notification 5.6：镜像权限变更邮件（D7 去重） =====

    /**
     * 镜像权限变更邮件：收件人 = 新增被共享个人 ∪ 原可见用户（去重，排除所有者）。
     * 按 relation 分两批经 {@link EmailService#sendAtBatch} 异步发送（同一批共享 ctx）：
     * 新增者 relation=SHARED_TO，原可见用户 relation=ALREADY_VISIBLE。
     *
     * @param imageId        镜像 ID
     * @param newlySharedIds 本次新增被共享的用户 ID（可见性变更时为空集）
     * @param changeSummary  变更摘要（用于操作日志与正文）
     */
    private void sendImagePermissionEmails(Long imageId, Set<Long> newlySharedIds, String changeSummary) {
        ImageMetadata image = getImage(imageId);
        String sharerName = emailService.resolveOperatorName();
        Set<Long> newlySet = newlySharedIds == null ? Set.of() : newlySharedIds;

        // 原可见用户 = 已存在 image_share 记录（排除所有者与新增者）
        List<User> alreadyVisibleUsers = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (ImageShare s : shareRepository.findByImageId(imageId)) {
            Long uid = s.getSharedToUserId();
            if (uid.equals(image.getOwnerId()) || newlySet.contains(uid) || !seen.add(uid)) continue;
            User u = userRepository.findById(uid).orElse(null);
            if (u != null && u.getEmail() != null && !u.getEmail().isBlank()) alreadyVisibleUsers.add(u);
        }
        // 新增被共享个人（排除所有者）
        List<User> newlySharedUsers = new ArrayList<>();
        for (Long uid : newlySet) {
            if (uid.equals(image.getOwnerId())) continue;
            User u = userRepository.findById(uid).orElse(null);
            if (u != null && u.getEmail() != null && !u.getEmail().isBlank()) newlySharedUsers.add(u);
        }

        String imageName = image.getName() + ":" + image.getTag();
        Instant now = Instant.now();
        if (!newlySharedUsers.isEmpty()) {
            Map<String, Object> ctx = baseImageCtx(imageName, sharerName, changeSummary, now);
            ctx.put("relation", "SHARED_TO");
            emailService.sendAtBatch(EmailTrigger.IMAGE_PERMISSION_CHANGED, newlySharedUsers, ctx);
        }
        if (!alreadyVisibleUsers.isEmpty()) {
            Map<String, Object> ctx = baseImageCtx(imageName, sharerName, changeSummary, now);
            ctx.put("relation", "ALREADY_VISIBLE");
            emailService.sendAtBatch(EmailTrigger.IMAGE_PERMISSION_CHANGED, alreadyVisibleUsers, ctx);
        }
    }

    private Map<String, Object> baseImageCtx(String imageName, String sharerName, String changeSummary, Instant now) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("operatorName", sharerName);
        ctx.put("time", now);
        ctx.put("imageName", imageName);
        ctx.put("sharerName", sharerName);
        ctx.put("changeSummary", changeSummary);
        return ctx;
    }

    private void checkOwnership(Long imageId) {
        ImageMetadata image = getImage(imageId);
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN
                && !image.getOwnerId().equals(SecurityUtils.getCurrentUserId())) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅所有者可操作");
        }
    }

    /** 解析后的 tar 元数据（name/tag 可空，app_ports 来自 ExposedPorts） */
    public record TarMetadata(String name, String tag, List<Integer> appPorts) {}

    /**
     * 解析 docker save 生成的 tar：manifest.json -> 镜像 config JSON -> RepoTags / ExposedPorts。
     * 自包含最小 tar 读取（无外部依赖），支持可选 gzip。
     */
    public TarMetadata parseTarMetadata(Path tarPath) {
        try (InputStream raw = new java.io.BufferedInputStream(Files.newInputStream(tarPath))) {
            return parseTarMetadata(raw);
        } catch (Exception e) {
            log.warn("[Image] tar 解析失败（将不预填）: {}", e.getMessage());
            return new TarMetadata(null, null, new ArrayList<>());
        }
    }

    /**
     * 从输入流解析 tar 元数据（不落盘，platform-refinements 任务 4.1）。
     * 供 /images/parse-tar 选定时即时解析回填表单。
     */
    public TarMetadata parseTarMetadata(InputStream rawInput) {
        String repoTag = null;
        List<Integer> ports = new ArrayList<>();
        try {
            InputStream in = maybeGzip(new java.io.BufferedInputStream(rawInput));
            Map<String, byte[]> jsonFiles = new HashMap<>();
            byte[] header = new byte[512];
            while (readFully(in, header) == 512) {
                if (isZeroBlock(header)) break;
                String entryName = readCString(header, 0, 100);
                long size = readOctal(header, 124, 12);
                byte type = header[156];
                boolean isFile = type == '0' || type == 0;
                // 仅缓存小型 JSON 条目（manifest.json 与 config），大层文件直接跳过
                if (isFile && entryName != null && entryName.endsWith(".json") && size <= 2_000_000) {
                    byte[] content = new byte[(int) size];
                    readFully(in, content);
                    jsonFiles.put(entryName, content);
                } else {
                    skipFully(in, size);
                }
                long padding = (512 - (size % 512)) % 512;
                skipFully(in, padding);
            }
            // 解析 manifest.json
            byte[] manifest = jsonFiles.get("manifest.json");
            if (manifest != null) {
                JsonNode root = objectMapper.readTree(manifest);
                if (root.isArray() && !root.isEmpty()) {
                    JsonNode first = root.get(0);
                    JsonNode repoTags = first.get("RepoTags");
                    if (repoTags != null && repoTags.isArray() && !repoTags.isEmpty()) {
                        String rt = repoTags.get(0).asText();
                        if (!"null".equals(rt) && !rt.isBlank()) repoTag = rt;
                    }
                    JsonNode configField = first.get("Config");
                    if (configField != null) {
                        byte[] configBytes = jsonFiles.get(configField.asText());
                        if (configBytes != null) {
                            JsonNode config = objectMapper.readTree(configBytes);
                            JsonNode exposed = config.path("config").path("ExposedPorts");
                            Iterator<String> it = exposed.fieldNames();
                            while (it.hasNext()) {
                                int p = parsePortNum(it.next());
                                if (p > 0) ports.add(p);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Image] tar 解析失败（将不预填）: {}", e.getMessage());
        }
        Collections.sort(ports);
        String parsedName = null, parsedTag = null;
        if (repoTag != null) {
            String[] parts = repoTag.split(":", 2);
            parsedName = parts[0];
            parsedTag = parts.length > 1 ? parts[1] : "latest";
        }
        return new TarMetadata(parsedName, parsedTag, ports);
    }

    private InputStream maybeGzip(InputStream in) throws IOException {
        in.mark(2);
        byte[] magic = new byte[2];
        int n = in.read(magic);
        in.reset();
        if (n == 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b) {
            return new GZIPInputStream(in);
        }
        return in;
    }

    private int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    private void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        byte[] discard = new byte[8192];
        while (remaining > 0) {
            int toRead = (int) Math.min(remaining, discard.length);
            int r = in.read(discard, 0, toRead);
            if (r < 0) break;
            remaining -= r;
        }
    }

    private boolean isZeroBlock(byte[] header) {
        for (byte b : header) {
            if (b != 0) return false;
        }
        return true;
    }

    private String readCString(byte[] buf, int off, int len) {
        int end = off;
        while (end < off + len && buf[end] != 0) end++;
        return new String(buf, off, end - off).trim();
    }

    private long readOctal(byte[] buf, int off, int len) {
        String s = readCString(buf, off, len).trim();
        if (s.isEmpty()) return 0;
        try {
            // 处理可能的前导空格与 base-256（GNU）标记，这里按八进制解析
            if ((s.charAt(0) & 0x80) != 0) return 0; // base-256 非预期
            return Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int parsePortNum(String portSpec) {
        // 如 "8888/tcp" 或 "22/tcp"
        if (portSpec == null) return 0;
        String p = portSpec.split("/", 2)[0];
        try {
            return Integer.parseInt(p.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
