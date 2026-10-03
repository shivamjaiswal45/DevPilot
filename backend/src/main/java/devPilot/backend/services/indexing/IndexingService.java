
// package devPilot.backend.services.indexing;

// import java.time.Instant;
// import java.util.ArrayList;
// import java.util.List;
// import java.util.Map;
// import java.util.UUID;

// import org.springframework.stereotype.Service;
// import org.springframework.ai.document.Document;
// import org.springframework.ai.vectorstore.VectorStore;
// import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.scheduling.annotation.Async;

// import devPilot.backend.entity.IndexStatus;
// import devPilot.backend.entity.Repository;
// import devPilot.backend.exceptions.BadRequestException;
// import devPilot.backend.exceptions.NotFoundException;
// import devPilot.backend.repository.RepositoryRepository;
// import devPilot.backend.services.UserService;
// import devPilot.backend.services.ai.RagSettings;
// import devPilot.backend.services.github.GitHubRateLimiter;
// import devPilot.backend.services.github.GithubApiClient;
// import jakarta.transaction.Transactional;
// import lombok.RequiredArgsConstructor;
// import lombok.extern.slf4j.Slf4j;

// @Service
// @RequiredArgsConstructor
// @Slf4j
// public class IndexingService {
//     private static final int VECTOR_BATCH_SIZE = 32;
//     private static final int PROGRESS_EVERY_N_FILES = 5;

//     private final RepositoryRepository repositoryRepository;
//     private final UserService userService;
//     private final GithubApiClient gitHubApiClient;
//     private final CodeFileFilter fileFilter;
//     private final CodeChunker codeChunker;
//     private final GitHubRateLimiter rateLimiter;
//     private final VectorStore vectorStore;

//     @Value("${app.indexing.max-file-bytes:102400}")
//     private long maxFileBytes;

//     public Repository startIndexing(UUID repoId, UUID userId) {
//         Repository repo = repositoryRepository.findByIdAndUserId(repoId, userId)
//                 .orElseThrow(() -> new NotFoundException("Repository not found"));

//         if (repo.getIndexStatus() == IndexStatus.INDEXING) {
//             throw new BadRequestException("Repository is already being indexed");
//         }

//         repo.setIndexStatus(IndexStatus.INDEXING);
//         repo.setFilesProcessed(0);
//         repo.setFilesTotal(0);
//         repo.setChunkCount(0);
//         repo.setErrorMessage(null);
//         repo.setUpdatedAt(Instant.now());
//         return repositoryRepository.save(repo);
//     }

//     @Async("indexingExecutor")
//      public void indexAsync(UUID repoId, UUID userId) {
//         try {
//             doIndex(repoId, userId);
//         } catch (Exception ex) {
//             log.error("Indexing failed for repo {}", repoId, ex);
//             markFailed(repoId, ex.getMessage());
//         }
//     }


//       private void doIndex(UUID repoId, UUID userId) {
//         Repository repo = repositoryRepository.findById(repoId)
//                 .orElseThrow(() -> new NotFoundException("Repository not found"));
//         String token = userService.decryptAccessToken(userService.requiredById(userId));

//         deleteExistingVectors(repoId.toString());

//         Map<String, Object> tree = gitHubApiClient.getRepoTree(
//                 token, repo.getOwner(), repo.getName(), repo.getDefaultBranch());
//         List<String> filePaths = listIndexableFiles(tree);

//         updateProgress(repoId, filePaths.size(), 0, 0, IndexStatus.INDEXING, null);

//         List<Document> batch = new ArrayList<>();
//         int processed = 0;
//         int totalChunks = 0;

//         for (String path : filePaths) {
//             List<Document> chunks = List.of();
//             try {
//                 String content = gitHubApiClient.getFileContent(
//                         token, repo.getOwner(), repo.getName(), path);
//                 chunks = codeChunker.chunkFile(repoId.toString(), path, content);
//             } catch (Exception ex) {
//                 log.warn("Skipping file {} in {}: {}", path, repo.getFullName(), ex.getMessage());
//             }

//             batch.addAll(chunks);
//             totalChunks += chunks.size();
//             if (batch.size() >= VECTOR_BATCH_SIZE) {
//                 vectorStore.add(batch);
//                 batch.clear();
//             }

//             processed++;
//             if (processed % PROGRESS_EVERY_N_FILES == 0 || processed == filePaths.size()) {
//                 updateProgress(repoId, filePaths.size(), processed, totalChunks, IndexStatus.INDEXING, null);
//             }
//             rateLimiter.pause();
//         }

//         if (!batch.isEmpty()) {
//             vectorStore.add(batch);
//         }

//         markReady(repoId, filePaths.size(), processed, totalChunks, repo.getFullName());
//     }


//        @SuppressWarnings("unchecked")
//     private List<String> listIndexableFiles(Map<String, Object> tree) {
//         if (tree == null || tree.get("tree") == null) {
//             return List.of();
//         }

//         List<Map<String, Object>> entries = (List<Map<String, Object>>) tree.get("tree");
//         return entries.stream()
//                 .filter(entry -> "blob".equals(String.valueOf(entry.get("type"))))
//                 .filter(entry -> {
//                     String path = String.valueOf(entry.get("path"));
//                     long size = entry.get("size") instanceof Number n ? n.longValue() : 0L;
//                     return fileFilter.isEligible(path, size, maxFileBytes);
//                 })
//                 .map(entry -> String.valueOf(entry.get("path")))
//                 .toList();
//     }

//      private void deleteExistingVectors(String repoId) {
//         try {
//             var filter = new FilterExpressionBuilder().eq(RagSettings.METADATA_REPO_ID, repoId).build();
//             vectorStore.delete(filter);
//         } catch (Exception ex) {
//             log.warn("Could not delete existing vectors for repo {}: {}", repoId, ex.getMessage());
//         }
//     };

//       @Transactional
//     protected void updateProgress(
//             UUID repoId,
//             int total,
//             int processed,
//             int chunks,
//             IndexStatus status,
//             String error) {
//         repositoryRepository.findById(repoId).ifPresent(repo -> {
//             repo.setFilesTotal(total);
//             repo.setFilesProcessed(processed);
//             repo.setChunkCount(chunks);
//             repo.setIndexStatus(status);
//             repo.setErrorMessage(error);
//             repo.setUpdatedAt(Instant.now());
//             repositoryRepository.save(repo);
//         });
//     }

//       @Transactional
//     protected void markReady(UUID repoId, int totalFiles, int processedFiles, int totalChunks, String fullName) {
//         repositoryRepository.findById(repoId).ifPresent(repo -> {
//             repo.setIndexStatus(IndexStatus.READY);
//             repo.setFilesTotal(totalFiles);
//             repo.setFilesProcessed(processedFiles);
//             repo.setChunkCount(totalChunks);
//             repo.setIndexedAt(Instant.now());
//             repo.setErrorMessage(null);
//             repo.setUpdatedAt(Instant.now());
//             repositoryRepository.save(repo);
//         });
//         log.info("Indexed {} files ({} chunks) for {}", processedFiles, totalChunks, fullName);
//     }

//      @Transactional
//     protected void markFailed(UUID repoId, String message) {
//         repositoryRepository.findById(repoId).ifPresent(repo -> {
//             repo.setIndexStatus(IndexStatus.FAILED);
//             repo.setErrorMessage(message != null && message.length() > 2000
//                     ? message.substring(0, 2000)
//                     : message);
//             repo.setUpdatedAt(Instant.now());
//             repositoryRepository.save(repo);
//         });
//     }

// }
package devPilot.backend.services.indexing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;

import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.Repository;
import devPilot.backend.exceptions.BadRequestException;
import devPilot.backend.exceptions.NotFoundException;
import devPilot.backend.repository.RepositoryRepository;
import devPilot.backend.services.UserService;
import devPilot.backend.services.ai.RagSettings;
import devPilot.backend.services.github.GitHubRateLimiter;
import devPilot.backend.services.github.GithubApiClient;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingService {
    private static final int VECTOR_BATCH_SIZE = 32;
    private static final int PROGRESS_EVERY_N_FILES = 5;

    // Gemini's free tier allows 100 embedding requests per minute.
    // Stay under it so large repositories do not fail with HTTP 429.
    private static final int MAX_EMBEDS_PER_MINUTE = 80;
    private static final long WINDOW_MS = 60_000;
    private static final long RETRY_WAIT_MS = 45_000;
    private static final int MAX_RETRIES = 4;

    private long windowStartMs = System.currentTimeMillis();
    private int embeddedInWindow = 0;

    private final RepositoryRepository repositoryRepository;
    private final UserService userService;
    private final GithubApiClient gitHubApiClient;
    private final CodeFileFilter fileFilter;
    private final CodeChunker codeChunker;
    private final GitHubRateLimiter rateLimiter;
    private final VectorStore vectorStore;

    @Value("${app.indexing.max-file-bytes:102400}")
    private long maxFileBytes;

    public Repository startIndexing(UUID repoId, UUID userId) {
        Repository repo = repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        if (repo.getIndexStatus() == IndexStatus.INDEXING) {
            throw new BadRequestException("Repository is already being indexed");
        }

        repo.setIndexStatus(IndexStatus.INDEXING);
        repo.setFilesProcessed(0);
        repo.setFilesTotal(0);
        repo.setChunkCount(0);
        repo.setErrorMessage(null);
        repo.setUpdatedAt(Instant.now());
        return repositoryRepository.save(repo);
    }

    @Async("indexingExecutor")
     public void indexAsync(UUID repoId, UUID userId) {
        try {
            doIndex(repoId, userId);
        } catch (Exception ex) {
            log.error("Indexing failed for repo {}", repoId, ex);
            markFailed(repoId, ex.getMessage());
        }
    }


      private void doIndex(UUID repoId, UUID userId) {
        Repository repo = repositoryRepository.findById(repoId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        String token = userService.decryptAccessToken(userService.requiredById(userId));

        deleteExistingVectors(repoId.toString());

        Map<String, Object> tree = gitHubApiClient.getRepoTree(
                token, repo.getOwner(), repo.getName(), repo.getDefaultBranch());
        List<String> filePaths = listIndexableFiles(tree);

        updateProgress(repoId, filePaths.size(), 0, 0, IndexStatus.INDEXING, null);

        List<Document> batch = new ArrayList<>();
        int processed = 0;
        int totalChunks = 0;

        for (String path : filePaths) {
            List<Document> chunks = List.of();
            try {
                String content = gitHubApiClient.getFileContent(
                        token, repo.getOwner(), repo.getName(), path);
                chunks = codeChunker.chunkFile(repoId.toString(), path, content);
            } catch (Exception ex) {
                log.warn("Skipping file {} in {}: {}", path, repo.getFullName(), ex.getMessage());
            }

            batch.addAll(chunks);
            totalChunks += chunks.size();
            if (batch.size() >= VECTOR_BATCH_SIZE) {
                addThrottled(batch);
                batch.clear();
            }

            processed++;
            if (processed % PROGRESS_EVERY_N_FILES == 0 || processed == filePaths.size()) {
                updateProgress(repoId, filePaths.size(), processed, totalChunks, IndexStatus.INDEXING, null);
            }
            rateLimiter.pause();
        }

        if (!batch.isEmpty()) {
            addThrottled(batch);
        }

        markReady(repoId, filePaths.size(), processed, totalChunks, repo.getFullName());
    }


    private void addThrottled(List<Document> docs) {
        for (int i = 0; i < docs.size(); i += MAX_EMBEDS_PER_MINUTE) {
            List<Document> part = docs.subList(i, Math.min(i + MAX_EMBEDS_PER_MINUTE, docs.size()));
            waitForEmbedBudget(part.size());
            addWithRetry(part);
        }
    }

    private synchronized void waitForEmbedBudget(int count) {
        long now = System.currentTimeMillis();
        if (now - windowStartMs >= WINDOW_MS) {
            windowStartMs = now;
            embeddedInWindow = 0;
        }
        if (embeddedInWindow + count > MAX_EMBEDS_PER_MINUTE) {
            long waitMs = WINDOW_MS - (now - windowStartMs) + 1_000;
            log.info("Embedding limit reached, pausing {}s to stay within the free-tier quota", waitMs / 1000);
            sleep(waitMs);
            windowStartMs = System.currentTimeMillis();
            embeddedInWindow = 0;
        }
        embeddedInWindow += count;
    }

    private void addWithRetry(List<Document> docs) {
        for (int attempt = 1; ; attempt++) {
            try {
                vectorStore.add(docs);
                return;
            } catch (RuntimeException ex) {
                String msg = ex.getMessage() == null ? "" : ex.getMessage();
                boolean quotaError = msg.contains("429") || msg.toLowerCase().contains("quota");
                if (!quotaError || attempt >= MAX_RETRIES) {
                    throw ex;
                }
                log.warn("Embedding quota hit (attempt {}/{}), waiting {}s before retrying",
                        attempt, MAX_RETRIES, RETRY_WAIT_MS / 1000);
                sleep(RETRY_WAIT_MS);
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Indexing was interrupted", e);
        }
    }

       @SuppressWarnings("unchecked")
    private List<String> listIndexableFiles(Map<String, Object> tree) {
        if (tree == null || tree.get("tree") == null) {
            return List.of();
        }

        List<Map<String, Object>> entries = (List<Map<String, Object>>) tree.get("tree");
        return entries.stream()
                .filter(entry -> "blob".equals(String.valueOf(entry.get("type"))))
                .filter(entry -> {
                    String path = String.valueOf(entry.get("path"));
                    long size = entry.get("size") instanceof Number n ? n.longValue() : 0L;
                    return fileFilter.isEligible(path, size, maxFileBytes);
                })
                .map(entry -> String.valueOf(entry.get("path")))
                .toList();
    }

     private void deleteExistingVectors(String repoId) {
        try {
            var filter = new FilterExpressionBuilder().eq(RagSettings.METADATA_REPO_ID, repoId).build();
            vectorStore.delete(filter);
        } catch (Exception ex) {
            log.warn("Could not delete existing vectors for repo {}: {}", repoId, ex.getMessage());
        }
    };

      @Transactional
    protected void updateProgress(
            UUID repoId,
            int total,
            int processed,
            int chunks,
            IndexStatus status,
            String error) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setFilesTotal(total);
            repo.setFilesProcessed(processed);
            repo.setChunkCount(chunks);
            repo.setIndexStatus(status);
            repo.setErrorMessage(error);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
    }

      @Transactional
    protected void markReady(UUID repoId, int totalFiles, int processedFiles, int totalChunks, String fullName) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.READY);
            repo.setFilesTotal(totalFiles);
            repo.setFilesProcessed(processedFiles);
            repo.setChunkCount(totalChunks);
            repo.setIndexedAt(Instant.now());
            repo.setErrorMessage(null);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
        log.info("Indexed {} files ({} chunks) for {}", processedFiles, totalChunks, fullName);
    }

     @Transactional
    protected void markFailed(UUID repoId, String message) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.FAILED);
            repo.setErrorMessage(message != null && message.length() > 2000
                    ? message.substring(0, 2000)
                    : message);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
    }

}