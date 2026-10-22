package com.sep490.vsa.feature.blog;

/**
 * Posts, questions, comments and answers of LOCKED or DELETED accounts are hidden everywhere Members and Guests read
 * content (user decision): the feed and search, statistics, featured posts, detail pages, Hỏi-Đáp, "Đã lưu", Home and
 * scam warnings. Nothing is deleted, so the content of an unlocked account shows again by itself.
 * <p>
 * Every read of {@code blogs} or {@code comments} for display adds {@link #of}, so the rule is written once.
 * {@code blogs.comment_count} and "Bài nổi bật" follow the same rule; {@link AuthorStatusSync} keeps them right when
 * an account's status changes. Home also applies it to {@code reviews} (its numbers, quotes and "Review mới"), and
 * {@code /media/} serves a photo publicly only from a post or review of an active author
 * ({@link com.sep490.vsa.feature.media.MediaAccess}).
 */
public final class ActiveAuthors {

    private ActiveAuthors() {
    }

    /**
     * SQL condition: the author ({@code author_id}) of the {@code blogs}, {@code comments} or {@code reviews} row with
     * this alias is an ACTIVE account.
     * The inner alias is derived from {@code alias}, so the condition can appear several times in one query.
     */
    public static String of(String alias) {
        String user = "au_" + alias;
        return "EXISTS (SELECT 1 FROM users " + user + " WHERE " + user + ".id = " + alias + ".author_id AND "
                + user + ".status = 'ACTIVE')";
    }
}
