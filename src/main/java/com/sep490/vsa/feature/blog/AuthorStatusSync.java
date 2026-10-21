package com.sep490.vsa.feature.blog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps what is computed from visible content right when an account is locked, unlocked or deleted. Content of LOCKED
 * or DELETED accounts is hidden ({@link ActiveAuthors}), so:
 * <ul>
 *   <li>{@code blogs.comment_count} (shown on the cards) of every post the account commented on is recounted: its
 *       comments leave the count, and come back when the account is unlocked;</li>
 *   <li>"Bài nổi bật" is recomputed once the change is committed, so a hidden post's place goes to the next post at
 *       once, and an unlocked author's post can be featured again without waiting for the hourly run.</li>
 * </ul>
 * Called inside the transaction that changes {@code users.status} (AdminUserService, ProfileService):
 * {@link #beforeStatusChange} before the users row is written, {@link #afterStatusChange} after it.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AuthorStatusSync {

    private final BlogDetailRepository repository;
    private final FeaturedSnapshotService featured;

    public AuthorStatusSync(BlogDetailRepository repository, FeaturedSnapshotService featured) {
        this.repository = repository;
        this.featured = featured;
    }

    /** Locks the posts the user commented on: post rows first, users row later, like every comment action. */
    public void beforeStatusChange(long userId) {
        repository.lockPostsCommentedBy(userId);
    }

    /** The new status must already be written in this transaction. */
    public void afterStatusChange(long userId) {
        repository.recountPostsCommentedBy(userId);
        featured.refreshAfterCommit();
    }
}
