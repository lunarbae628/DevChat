package project.backend.domain.community.dto.event;

public record ApplicantResultEvent(
    Long applicantMemberId,
    String applicantUsername,
    Long authorId,
    String authorNickname,
    Long postId,
    String postTitle,
    boolean approved
) {

}
