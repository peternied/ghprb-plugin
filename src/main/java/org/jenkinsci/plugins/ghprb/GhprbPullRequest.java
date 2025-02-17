package org.jenkinsci.plugins.ghprb;

import com.google.common.base.Joiner;
import hudson.model.Run;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.github.GHCommitPointer;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestCommitDetail;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitUser;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Maintains state about a Pull Request for a particular Jenkins job. This is what understands the current state of a PR
 * for a particular job.
 *
 * @author Honza Brázdil jbrazdil@redhat.com
 */
public class GhprbPullRequest {

    private static final Logger LOGGER = Logger.getLogger(GhprbPullRequest.class.getName());

    @Deprecated
    @SuppressWarnings("unused")
    private transient GHUser author;

    @Deprecated
    @SuppressWarnings("unused")
    private transient String title;

    @Deprecated
    @SuppressWarnings("unused")
    private transient String reponame;

    @Deprecated
    @SuppressWarnings("unused")
    private transient URL url;

    @Deprecated
    @SuppressWarnings("unused")
    private transient String description;

    @Deprecated
    @SuppressWarnings("unused")
    private transient String target;

    @Deprecated
    @SuppressWarnings("unused")
    private transient String source;

    @Deprecated
    @SuppressWarnings("unused")
    private transient String authorRepoGitUrl;

    @Deprecated
    @SuppressWarnings("unused")
    private transient Boolean changed = true;

    private transient String authorEmail;

    private transient Ghprb helper; // will be refreshed each time GhprbRepository.init() is called

    private transient GhprbRepository repo; // will be refreshed each time GhprbRepository.init() is called

    private transient GHPullRequest pr;

    private transient GHUser triggerSender; // Only needed for a single build

    private transient GitUser commitAuthor; // Only needed for a single build

    private transient String commentBody;

    private transient boolean shouldRun = false; // Declares if we should run the build this time.

    private transient boolean triggered = false; // Only lets us know if the trigger phrase was used for this run

    private transient boolean mergeable = false; // Only works as an easy way to pass the value around for the start of

    // this build
    // Only useful for webhooks.  We want to avoid excessive use of
    // Github API calls, specifically comment checks.  In updatePR, we check
    // for comments that may have occurred between the previous update
    // and the current one.  However, if we are using webhooks, we will always
    // receive these events directly from github.  Unfortunately, simply avoiding
    // the comment check altogether when using webhooks will not be perfect
    // since a Jenkins restart could miss some comments.  This flag indicates that
    // an initial comment check has been done and we can now operate in pure
    // webhook mode.
    private transient boolean initialCommentCheckDone = false;

    private final int id;

    private Date updated; // Needed to track when the PR was updated

    private String head;

    private String base;

    private boolean accepted = false; // Needed to see if the PR has been added to the accepted list

    private String lastBuildId;

    public static String getRequestForTestingPhrase() {
        return GhprbTrigger.getDscp().getRequestForTestingPhrase();
    }

    // Sets the updated time of the PR.  If the updated time is newer,
    // return true, false otherwise.
    private boolean setUpdated(Date lastUpdateTime) {
        // Because there is no gaurantee of order of delivery,
        // we want to ensure that we do not set the updated time if it was
        // earlier than the current updated time
        if (updated == null || updated.compareTo(lastUpdateTime) < 0) {
            updated = lastUpdateTime;
            return true;
        }
        return false;
    }

    private void setHead(String newHead) {
        this.head = StringUtils.isEmpty(newHead) ? head : newHead;
    }

    private void setBase(String newBase) {
        this.base = StringUtils.isEmpty(newBase) ? base : newBase;
    }

    private void setAccepted(boolean shouldRun) {
        accepted = true;
        this.shouldRun = shouldRun;
    }

    public GhprbPullRequest(GHPullRequest pr,
                            Ghprb ghprb,
                            GhprbRepository repo) {

        id = pr.getNumber();
        setPullRequest(pr);

        this.helper = ghprb;

        this.repo = repo;
        GHUser author;
        try {
            author = pr.getUser();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String reponame = repo.getName();

        if (ghprb.isWhitelisted(author)) {
            setAccepted(true);
        } else {
            LOGGER.log(Level.INFO,
                    "Author of #{0} {1} on {2} not in whitelist!",
                    new Object[] {id, author.getLogin(), reponame});
            if (!containsComment(pr, getRequestForTestingPhrase()) && !ghprb.getTrigger().getDontPublishTestingPhrase()) {
                repo.addComment(id, GhprbTrigger.getDscp().getRequestForTestingPhrase());
            }
        }

        LOGGER.log(Level.INFO,
                "Created Pull Request #{0} on {1} by {2} ({3}) updated at: {4} SHA: {5}",
                new Object[] {id, reponame, author.getLogin(), getAuthorEmail(), updated, this.head});
    }

    /**
     * Checks whether the specific PR contains a comment with the expected body.
     *
     * @return true if the PR contains comment with the specified body, otherwise false
     */
    private boolean containsComment(GHPullRequest ghPullRequest, String expectedBody) {
        List<GHIssueComment> prComments;
        try {
            prComments = ghPullRequest.getComments();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to get comments for PR " + ghPullRequest, e);
            // return false in case of an error - probably safer to have multiple comments than possibly none
            return false;
        }
        for (GHIssueComment comment : prComments) {
            if (comment.getBody() != null && comment.getBody().equals(expectedBody)) {
                return true;
            }
        }
        return false;
    }

    public void init(Ghprb helper,
                     GhprbRepository repo) {
        this.helper = helper;
        this.repo = repo;
    }

    /**
     * Checks this Pull Request representation against a GitHub version of the Pull Request, and triggers a build if
     * necessary.
     *
     * @param ghpr      the pull request from github
     * @param isWebhook whether this is from a webhook or not
     */
    public void check(GHPullRequest ghpr, boolean isWebhook) {
        if (helper.isProjectDisabled()) {
            LOGGER.log(Level.FINE, "Project is disabled, ignoring pull request");
            return;
        }
        // Call update PR with the update PR info and no comment
        updatePR(ghpr, null /*GHIssueComment*/, isWebhook);
        commitAuthor = getPRCommitAuthor();
        checkSkipBuild();
        checkBlackListLabels();
        checkWhiteListLabels();
        tryBuild();
    }

    private void checkBlackListLabels() {
        Set<String> labelsToIgnore = helper.getBlackListLabels();
        if (labelsToIgnore != null && !labelsToIgnore.isEmpty()) {
            try {
                for (GHLabel label : pr.getLabels()) {
                    if (labelsToIgnore.contains(label.getName())) {
                        LOGGER.log(Level.INFO,
                                "Found label {0} in ignore list, pull request will be ignored.",
                                label.getName());
                        shouldRun = false;
                    }
                }
            } catch (Error e) {
                LOGGER.log(Level.SEVERE, "Failed to read blacklist labels", e);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to read blacklist labels", e);
            }
        }
    }

    private void checkWhiteListLabels() {
        Set<String> labelsMustContain = helper.getWhiteListLabels();
        if (labelsMustContain != null && !labelsMustContain.isEmpty()) {
            boolean containsWhiteListLabel = false;
            try {
                for (GHLabel label : pr.getLabels()) {
                    if (labelsMustContain.contains(label.getName())) {
                        LOGGER.log(Level.INFO,
                                "Found label {0} in whitelist",
                                label.getName());
                        containsWhiteListLabel = true;
                    }
                }

                if (!containsWhiteListLabel) {
                    LOGGER.log(Level.INFO, "Can't find any of whitelist label.");
                    shouldRun = false;
                }
            } catch (Error e) {
                LOGGER.log(Level.SEVERE, "Failed to read whitelist labels", e);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to read whitelist labels", e);
            }
        }
    }

    private void checkSkipBuild() {
        synchronized (this) {
            String skipBuildPhrase = helper.checkSkipBuildPhrase(this.pr);
            if (!StringUtils.isEmpty(skipBuildPhrase)) {
                LOGGER.log(Level.INFO,
                        "Pull request commented with {0} skipBuildPhrase. Hence skipping the build.",
                        skipBuildPhrase);
                shouldRun = false;
                return;
            }
            if (commitAuthor == null) {
                return;
            }
            String blackListCommitAuthor = helper.checkBlackListCommitAuthor(commitAuthor.getName());
            if (!StringUtils.isEmpty(blackListCommitAuthor)) {
                LOGGER.log(Level.FINE,
                        "Pull request triggered by user: {0}. Skipping build because that user is blacklisted.",
                        blackListCommitAuthor);
                shouldRun = false;
            }
        }
    }

    public void check(GHIssueComment comment) {
        if (helper.isProjectDisabled()) {
            LOGGER.log(Level.FINE, "Project is disabled, ignoring comment");
            return;
        }

        updatePR(null /*GHPullRequest*/, comment, true);
        // reset PR commit author
        commitAuthor = null;
        checkSkipBuild();
        checkBlackListLabels();
        checkWhiteListLabels();
        tryBuild();
    }

    /**
     * Reconcile the view of the PR we have locally with the one that was sent to us by GH.
     * We can reach this method in one of three ways, and the comment indicates what
     * we should do in each case:
     * 1. With webhooks + new trigger/PR initialization -
     * This could happen if a new job was added, new trigger was enabled, or if Jenkins
     * was restarted.  In this case, our view of the PR is out of date.  We need to
     * compare hashes and check the comments going back to when the last update was (which could be
     * when the PR was created).
     * 2. With webhooks + new comment/PR update - This is "normal" operation.  In these
     * cases, we only need to process the comment that was just added, or compare hashes with
     * the updated PR info (for instance, if someone changes a title of a PR it shouldn't trigger.
     * We do NOT need to pull the comment info, since we will have gotten or will get
     * each comment.
     * 3. Without webhooks - In this case, we will always check comments and hashes until
     * the last update time.
     */
    private void updatePR(GHPullRequest ghpr, GHIssueComment comment, boolean isWebhook) {
        // Get the updated time
        try {
            Date lastUpdateTime = updated;
            Date updatedDate = comment != null ? comment.getUpdatedAt() : ghpr.getUpdatedAt();
            // Don't log unless it was actually updated
            if (updated == null || updated.compareTo(updatedDate) < 0) {
                String user = comment != null ? comment.getUser().getName() : ghpr.getUser().getName();
                LOGGER.log(
                        Level.INFO,
                        "Pull request #{0} was updated/initialized on {1} at {2} by {3} ({4})",
                        new Object[] {this.id, this.repo.getName(), updatedDate, user,
                                comment != null ? "comment" : "PR update"}
                );
            }

            synchronized (this) {
                boolean wasUpdated = setUpdated(updatedDate);

                // Update the PR object with the new pull request object if
                // it is non-null.  getPullRequest will then avoid another
                // GH API call.
                if (ghpr != null) {
                    setPullRequest(ghpr);
                }

                // Grab the pull request for use in this method (in case we came in through the comment path)
                GHPullRequest pullRequest = getPullRequest();

                // the author of the PR could have been whitelisted since its creation
                if (!accepted && helper.isWhitelisted(getPullRequestAuthor())) {
                    LOGGER.log(Level.INFO, "Pull request #{0}'s author has been whitelisted", new Object[] {id});
                    setAccepted(false);
                }

                // If we were passed a comment and are receiving all the comments
                // as they come in (e.g. webhooks), then we don't need to do anything but
                // check that comment.  Otherwise check the full set since the last
                // time we updated (which might have just happened).
                int commentsChecked = 0;
                //Setting to null fixes ghprbCommentBody containing stale values; ref https://github.com/jenkinsci/ghprb-plugin/pull/504
                commentBody = null;
                if (wasUpdated && (!isWebhook || !initialCommentCheckDone)) {
                    initialCommentCheckDone = true;
                    commentsChecked = checkComments(pullRequest, lastUpdateTime);
                } else if (comment != null) {
                    checkComment(comment);
                    commentsChecked = 1;
                }

                // Check the commit on the PR against the recorded version.
                boolean newCommit = checkCommit(pullRequest);

                // Log some info.
                if (!newCommit && commentsChecked == 0) {
                    LOGGER.log(Level.INFO, "Pull request #{0} was updated on repo {1} but there aren''t any new comments nor commits; "
                                    + "that may mean that commit status was updated.",
                            new Object[] {this.id, this.repo.getName()}
                    );
                }
            }
        } catch (Error e) {
            LOGGER.log(Level.SEVERE, "Exception caught while updating the PR", e);
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "Exception caught while updating the PR", ex);
        }
    }

    private boolean matchesAnyBranch(String target, List<GhprbBranch> branches) {
        for (GhprbBranch b : branches) {
            if (b.matches(target)) {
                // the target branch is in the whitelist!
                return true;
            }
        }
        return false;
    }

    // Determines whether a branch is an allowed target branch
    //
    // A branch is an allowed target branch if it matches a branch in the whitelist
    // but NOT any branches in the blacklist.
    public boolean isAllowedTargetBranch() {
        List<GhprbBranch> whiteListBranches = helper.getWhiteListTargetBranches();
        List<GhprbBranch> blackListBranches = helper.getBlackListTargetBranches();

        String target = getTarget();

        // First check if it matches any whitelist branch.  It matches if
        // the list is empty, or if it matches any branch in the list
        if (!whiteListBranches.isEmpty()) {
            if (!matchesAnyBranch(target, whiteListBranches)) {
                LOGGER.log(Level.FINEST,
                        "PR #{0} target branch: {1} isn''t in our whitelist of target branches: {2}",
                        new Object[] {id, target, Joiner.on(',').skipNulls().join(whiteListBranches)});
                return false;
            }
        }

        // We matched something in the whitelist, now check the blacklist. It must
        // not match any branch in the blacklist
        if (!blackListBranches.isEmpty()) {
            if (matchesAnyBranch(target, blackListBranches)) {
                LOGGER.log(Level.FINEST,
                        "PR #{0} target branch: {1} is in our blacklist of target branches: {2}",
                        new Object[] {id, target, Joiner.on(',').skipNulls().join(blackListBranches)});
                return false;
            }
        }

        return true;
    }

    private GitUser getPRCommitAuthor() {
        try {
            for (GHPullRequestCommitDetail commitDetails : pr.listCommits()) {
                if (commitDetails.getSha().equals(getHead())) {
                    return commitDetails.getCommit().getCommitter();
                }
            }
        } catch (Error e) {
            LOGGER.log(Level.INFO, "Unable to get PR commits: ", e);
        } catch (Exception ex) {
            LOGGER.log(Level.INFO, "Unable to get PR commits: ", ex);
        }
        return null;
    }

    boolean containsWatchedPaths(GHPullRequest pr) {
        synchronized (this) {
            List<Pattern> included = helper.getIncludedRegionPatterns();
            List<Pattern> excluded = helper.getExcludedRegionPatterns();

            // No need to perform a check if no regions are defined
            if (included.isEmpty() && excluded.isEmpty()) {
                return true;
            }

            List<String> paths = new ArrayList<String>();
            for (GHPullRequestFileDetail fileDetail : pr.listFiles().withPageSize(100)) {
                paths.add(fileDetail.getFilename());
            }

            // Assemble the list of included paths
            List<String> includedPaths = new ArrayList<String>(paths.size());
            if (!included.isEmpty()) {
                for (String path : paths) {
                    for (Pattern pattern : included) {
                        if (pattern.matcher(path).matches()) {
                            includedPaths.add(path);
                            break;
                        }
                    }
                }
            } else {
                includedPaths.addAll(paths);
            }

            // Assemble the list of excluded paths
            List<String> excludedPaths = new ArrayList<String>();
            if (!excluded.isEmpty()) {
                for (String path : includedPaths) {
                    for (Pattern pattern : excluded) {
                        if (pattern.matcher(path).matches()) {
                            excludedPaths.add(path);
                            break;
                        }
                    }
                }
            }

            if (excluded.isEmpty() && !included.isEmpty() && includedPaths.isEmpty()) {
                LOGGER.log(Level.FINEST, "No paths matched included region whitelist in the pull request");
                return false;
            } else if (includedPaths.size() == excludedPaths.size()) {
                // If every affected path is excluded, return true.
                LOGGER.log(Level.FINEST, "Found only excluded paths in the pull request");
                return false;
            }

            return true;
        }
    }

    private void tryBuild() {
        synchronized (this) {
            if (helper.isProjectDisabled()) {
                LOGGER.log(Level.FINEST, "Project is disabled, not trying to build");
                shouldRun = false;
                triggered = false;
            }
            if (helper.ifOnlyTriggerPhrase() && !triggered) {
                LOGGER.log(Level.FINEST, "Trigger only phrase but we are not triggered");
                shouldRun = false;
            }
            triggered = false; // Once we have decided that we are triggered then the flag should be set to false.

            if (!isAllowedTargetBranch()) {
                LOGGER.log(Level.FINEST, "Branch is not whitelisted or is blacklisted, skipping the build");
                return;
            }

            if (shouldRun && !containsWatchedPaths(pr)) {
                LOGGER.log(Level.FINEST, "Pull request contains no watched paths, skipping the build");
                shouldRun = false;
            }

            if (shouldRun) {
                shouldRun = false; // Change the shouldRun flag as soon as we decide to build.
                LOGGER.log(Level.FINEST, "Running the build");

                if (pr != null) {
                    LOGGER.log(Level.FINEST, "PR is not null, checking if mergable");
                    checkMergeable();
                    getPRCommitAuthor();
                }

                LOGGER.log(Level.FINEST, "Running build...");
                build();
            }
        }
    }

    private void build() {
        GhprbBuilds builder = helper.getBuilds();
        builder.build(this, triggerSender, commentBody);
    }

    // returns false if no new commit
    private boolean checkCommit(GHPullRequest pr) {
        GHCommitPointer head = pr.getHead();
        GHCommitPointer base = pr.getBase();

        String headSha = head.getSha();
        String baseSha = base.getSha();

        if (StringUtils.equals(headSha, this.head) && StringUtils.equals(baseSha, this.base)) {
            return false;
        }

        LOGGER.log(Level.FINE,
                "New commit. Sha: Head[{0} => {1}] Base[{2} => {3}]",
                new Object[] {this.head, headSha, this.base, baseSha});

        setHead(headSha);
        setBase(baseSha);

        if (accepted) {
            shouldRun = true;
        }
        return true;
    }

    private void checkComment(GHIssueComment comment) throws IOException {
        GHUser sender = comment.getUser();
        String body = comment.getBody();

        String senderName = sender.getName();
        LOGGER.log(Level.FINEST, "[{0}] Added comment: {1}", new Object[] {senderName != null ? senderName : sender.getLogin(), body});

        // Disabled until more advanced configs get set up
        // ignore comments from bot user, this fixes an issue where the bot would auto-whitelist
        // a user or trigger a build when the 'request for testing' phrase contains the
        // whitelist/trigger phrase and the bot is a member of a whitelisted organisation
        // if (helper.isBotUser(sender)) {
        // logger.log(Level.INFO, "Comment from bot user {0} ignored.", sender);
        // return;
        // }

        if (helper.isWhitelistPhrase(body) && helper.isAdmin(sender)) { // add to whitelist
            GHIssue parent = comment.getParent();
            GHUser author = parent.getUser();
            if (!helper.isWhitelisted(author)) {
                LOGGER.log(Level.FINEST, "Author {0} not whitelisted, adding to whitelist.", author);
                helper.addWhitelist(author.getLogin());
            }
            setAccepted(true);
        } else if (helper.isOktotestPhrase(body) && helper.isAdmin(sender)) { // ok to test
            LOGGER.log(Level.FINEST, "Admin {0} gave OK to test", sender);
            setAccepted(true);
        } else if (helper.isRetestPhrase(body)) { // test this please
            LOGGER.log(Level.FINEST, "Retest phrase");
            if (helper.isAdmin(sender)) {
                LOGGER.log(Level.FINEST, "Admin {0} gave retest phrase", sender);
                shouldRun = true;
            } else if (accepted && helper.isWhitelisted(sender)) {
                LOGGER.log(Level.FINEST, "Retest accepted and user {0} is whitelisted", sender);
                shouldRun = true;
            }
        } else if (helper.isTriggerPhrase(body)) { // trigger phrase
            LOGGER.log(Level.FINEST, "Trigger phrase");
            if (helper.isAdmin(sender)) {
                LOGGER.log(Level.FINEST, "Admin {0} ran trigger phrase", sender);
                shouldRun = true;
                triggered = true;
            } else if (accepted && helper.isWhitelisted(sender)) {
                LOGGER.log(Level.FINEST, "Trigger accepted and user {0} is whitelisted", sender);
                shouldRun = true;
                triggered = true;
            }
        }

        if (shouldRun) {
            triggerSender = sender;
            commentBody = body;
        }
    }

    private int checkComments(GHPullRequest ghpr,
                              Date lastUpdatedTime) {
//        It looks like this is always returning 0, ignoring till it can be confirmed.
//        if (ghpr.getCommentsCount() == 0) {
//            // Avoid the API call. Nothing to do here.
//            return 0;
//        }

        int count = 0;
        LOGGER.log(Level.FINEST, "Checking for comments after: {0}", lastUpdatedTime);
        try {
            for (GHIssueComment comment : ghpr.getComments()) {
                LOGGER.log(Level.FINEST, "Comment was made at: {0}", comment.getUpdatedAt());
                if (lastUpdatedTime.compareTo(comment.getUpdatedAt()) < 0) {
                    LOGGER.log(Level.FINEST, "Comment was made after last update time, {0}", comment.getBody());
                    count++;
                    try {
                        checkComment(comment);
                    } catch (IOException ex) {
                        LOGGER.log(Level.SEVERE, "Couldn't check comment #" + comment.getId(), ex);
                    }
                }
            }
        } catch (Error e) {
            LOGGER.log(Level.SEVERE, "Couldn't obtain comments.", e);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Couldn't obtain comments.", e);
        }
        return count;
    }

    public boolean checkMergeable() {
        try {
            int r = 5;
            Boolean isMergeable = pr.getMergeable();
            while (isMergeable == null && r-- > 0) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    break;
                }
                // If the mergeability state was unknown, we need
                // to grab the mergeability state from the server.
                this.getPullRequest(true);
                isMergeable = pr.getMergeable();
            }
            mergeable = isMergeable != null && isMergeable;
        } catch (Error e) {
            LOGGER.log(Level.SEVERE, "Couldn't obtain mergeable status.", e);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Couldn't obtain mergeable status.", e);
        }
        return mergeable;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof GhprbPullRequest)) {
            return false;
        }
        GhprbPullRequest o = (GhprbPullRequest) obj;
        return o.id == id;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 89 * hash + this.id;
        return hash;
    }

    public int getId() {
        return id;
    }

    public String getHead() {
        return head;
    }

    public String getAuthorRepoGitUrl() {
        GHCommitPointer prHead = pr.getHead();
        String authorRepoGitUrl = "";

        if (prHead != null && prHead.getRepository() != null) {
            authorRepoGitUrl = prHead.getRepository().getHttpTransportUrl();
        }
        return authorRepoGitUrl;
    }

    public boolean isMergeable() {
        return mergeable;
    }

    /**
     * Base and Ref are part of the PullRequest object
     *
     * @return the sha to the base
     */
    public String getTarget() {
        try {
            return getPullRequest().getBase().getRef();
        } catch (IOException e) {
            return "UNKNOWN";
        }
    }

    /**
     * Head and Ref are part of the PullRequest object
     *
     * @return the sha for the head.
     */
    public String getSource() {
        try {
            return getPullRequest().getHead().getRef();
        } catch (IOException e) {
            return "UNKNOWN";
        }
    }

    /**
     * Title is part of the PullRequest object
     *
     * @return the title of the pull request.
     */
    public String getTitle() {
        try {
            return getPullRequest().getTitle();
        } catch (IOException e) {
            return "UNKNOWN";
        }
    }

    /**
     * Returns the URL to the Github Pull Request.
     * This URL is part of the pull request object
     *
     * @return the Github Pull Request URL
     * @throws IOException If unable to connect to GitHub
     */
    public URL getUrl() throws IOException {
        return getPullRequest().getHtmlUrl();
    }

    /**
     * The description body is part of the PullRequest object
     *
     * @return the description from github
     */
    public String getDescription() {
        try {
            return getPullRequest().getBody();
        } catch (IOException e) {
            return "UNKNOWN";
        }
    }

    public GitUser getCommitAuthor() {
        return commitAuthor;
    }

    /**
     * Author is part of the PullRequest Object
     *
     * @return The GitHub user that created the PR
     * @throws IOException Unable to connect to GitHub
     */
    public GHUser getPullRequestAuthor() throws IOException {
        return getPullRequest().getUser();
    }

    /**
     * Get the PullRequest object for this PR
     *
     * @return a copy of the pull request
     * @throws IOException if unable to connect to GitHub
     */
    public GHPullRequest getPullRequest() throws IOException {
        return getPullRequest(false);
    }

    /**
     * Get the PullRequest object for this PR
     *
     * @param force If true, forces retrieval of the PR info from the github API. Use sparingly.
     * @return a copy of the pull request
     * @throws IOException if unable to connect to GitHub
     */
    public GHPullRequest getPullRequest(boolean force) throws IOException {
        if (this.pr == null || force) {
            setPullRequest(repo.getActualPullRequest(this.id));
        }
        return pr;
    }

    private void setPullRequest(GHPullRequest pr) {
        if (pr == null) {
            return;
        }
        synchronized (this) {
            this.pr = pr;

            try {
                if (updated == null) {
                    setUpdated(pr.getCreatedAt());
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Unable to get date for new PR", e);
                setUpdated(new Date());
            }

            if (StringUtils.isEmpty(this.head)) {
                GHCommitPointer prHead = pr.getHead();
                setHead(prHead.getSha());
            }

            if (StringUtils.isEmpty(this.base)) {
                GHCommitPointer prBase = pr.getBase();
                setBase(prBase.getSha());
            }
        }
    }

    /**
     * Email address is collected from GitHub as extra information, so lets cache it.
     *
     * @return The PR authors email address
     */
    public String getAuthorEmail() {
        if (StringUtils.isEmpty(authorEmail)) {
            try {
                GHUser user = getPullRequestAuthor();
                authorEmail = user.getEmail();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Unable to fetch author info for " + id);
            }
        }
        authorEmail = StringUtils.isEmpty(authorEmail) ? "" : authorEmail;
        return authorEmail;
    }

    public void setBuild(Run<?, ?> build) {
        lastBuildId = build.getId();
    }

    public String getLastBuildId() {
        return lastBuildId;
    }
}
