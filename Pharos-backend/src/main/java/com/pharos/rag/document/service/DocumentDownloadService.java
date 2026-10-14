package com.pharos.rag.document.service;

import com.pharos.rag.common.enums.DocumentStatus;
import com.pharos.rag.common.exception.BusinessException;
import com.pharos.rag.document.mapper.DocumentMapper;
import com.pharos.rag.document.model.entity.DocumentEntity;
import com.pharos.rag.document.model.vo.DocumentDownloadVO;
import com.pharos.rag.engine.storage.ObjectStorageService;
import com.pharos.rag.group.service.GroupMembershipService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;

/**
 * 文档下载服务。
 */
@Service
public class DocumentDownloadService {

    private final DocumentMapper documentMapper;
    private final GroupMembershipService groupMembershipService;
    private final ObjectStorageService objectStorageService;

    public DocumentDownloadService(DocumentMapper documentMapper,
                                   GroupMembershipService groupMembershipService,
                                   ObjectStorageService objectStorageService) {
        this.documentMapper = documentMapper;
        this.groupMembershipService = groupMembershipService;
        this.objectStorageService = objectStorageService;
    }

    /** 获取文档下载信息，需群组成员权限 */
    public DocumentDownloadVO downloadDocument(Long userId, Long groupId, Long documentId) {
        requireGroupId(groupId);
        groupMembershipService.requireGroupReadable(groupId);
        if (documentId == null || documentId <= 0) {
            throw new BusinessException("文档ID非法");
        }
        DocumentEntity document = documentMapper.selectByIdAndGroupId(documentId, groupId);
        if (document == null) {
            throw new BusinessException("文档不存在或已删除");
        }
        if (!DocumentStatus.READY.name().equals(document.getStatus())) {
            throw new BusinessException("文档尚未就绪，暂不可下载");
        }
        if (!StringUtils.hasText(document.getStorageBucket())
                || !StringUtils.hasText(document.getStorageObjectKey())) {
            throw new BusinessException("文档存储信息缺失");
        }
        InputStream inputStream = objectStorageService.getObject(
                document.getStorageBucket(), document.getStorageObjectKey());
        DocumentDownloadVO downloadInfo = new DocumentDownloadVO();
        downloadInfo.setInputStream(inputStream);
        downloadInfo.setFileName(document.getFileName());
        downloadInfo.setContentType(document.getContentType());
        downloadInfo.setFileSize(document.getFileSize());
        return downloadInfo;
    }

    private void requireGroupId(Long groupId) {
        if (groupId == null || groupId <= 0) {
            throw new BusinessException("groupId 非法");
        }
    }
}
