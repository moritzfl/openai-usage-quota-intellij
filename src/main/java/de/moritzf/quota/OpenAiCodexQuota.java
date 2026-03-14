package de.moritzf.quota;

import java.time.Instant;

/**
 * Aggregates parsed Codex usage quota data returned by the backend.
 */
public class OpenAiCodexQuota {
    private UsageWindow primary;
    private UsageWindow secondary;
    private UsageWindow reviewPrimary;
    private UsageWindow reviewSecondary;
    private String planType;
    private Boolean allowed;
    private Boolean limitReached;
    private Boolean reviewAllowed;
    private Boolean reviewLimitReached;
    private Instant fetchedAt;
    private String rawJson;
    private String accountId;
    private String email;

    public UsageWindow getPrimary() {
        return primary;
    }

    public void setPrimary(UsageWindow primary) {
        this.primary = primary;
    }

    public UsageWindow getSecondary() {
        return secondary;
    }

    public void setSecondary(UsageWindow secondary) {
        this.secondary = secondary;
    }

    public UsageWindow getReviewPrimary() {
        return reviewPrimary;
    }

    public void setReviewPrimary(UsageWindow reviewPrimary) {
        this.reviewPrimary = reviewPrimary;
    }

    public UsageWindow getReviewSecondary() {
        return reviewSecondary;
    }

    public void setReviewSecondary(UsageWindow reviewSecondary) {
        this.reviewSecondary = reviewSecondary;
    }

    public String getPlanType() {
        return planType;
    }

    public void setPlanType(String planType) {
        this.planType = planType;
    }

    public Boolean getAllowed() {
        return allowed;
    }

    public void setAllowed(Boolean allowed) {
        this.allowed = allowed;
    }

    public Boolean getLimitReached() {
        return limitReached;
    }

    public void setLimitReached(Boolean limitReached) {
        this.limitReached = limitReached;
    }

    public Boolean getReviewAllowed() {
        return reviewAllowed;
    }

    public void setReviewAllowed(Boolean reviewAllowed) {
        this.reviewAllowed = reviewAllowed;
    }

    public Boolean getReviewLimitReached() {
        return reviewLimitReached;
    }

    public void setReviewLimitReached(Boolean reviewLimitReached) {
        this.reviewLimitReached = reviewLimitReached;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    public String getRawJson() {
        return rawJson;
    }

    public void setRawJson(String rawJson) {
        this.rawJson = rawJson;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    @Override
    public String toString() {
        return "OpenAiCodexQuota{" +
                "primary=" + primary +
                ", secondary=" + secondary +
                ", reviewPrimary=" + reviewPrimary +
                ", reviewSecondary=" + reviewSecondary +
                ", planType='" + planType + '\'' +
                ", allowed=" + allowed +
                ", limitReached=" + limitReached +
                ", reviewAllowed=" + reviewAllowed +
                ", reviewLimitReached=" + reviewLimitReached +
                ", fetchedAt=" + fetchedAt +
                ", rawJson=" + (rawJson == null ? "null" : "<redacted>") +
                ", accountId=" + (accountId == null ? "null" : "<redacted>") +
                ", email=" + (email == null ? "null" : "<redacted>") +
                '}';
    }
}
