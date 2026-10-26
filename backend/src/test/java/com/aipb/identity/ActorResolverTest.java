package com.aipb.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActorResolverTest {

    @Test
    void theHeaderNamesTheRoleAndAnythingElseIsABanker() {
        assertThat(ActorResolver.resolve("builder")).isEqualTo(new Actor("demo-builder", Role.BUILDER));
        assertThat(ActorResolver.resolve(" Reviewer ").role()).isEqualTo(Role.REVIEWER);
        assertThat(ActorResolver.resolve(null).role()).isEqualTo(Role.BANKER);
        assertThat(ActorResolver.resolve("admin").role()).isEqualTo(Role.BANKER);
    }

    @Test
    void onlyBuildersAndReviewersMayAuthor() {
        assertThatThrownBy(() -> Actor.of(Role.BANKER).requireAuthor()).isInstanceOf(Actor.Forbidden.class);
        assertThat(Actor.of(Role.BUILDER).requireAuthor().id()).isEqualTo("demo-builder");
        assertThat(Actor.of(Role.REVIEWER).role().canReview()).isTrue();
        assertThat(Actor.of(Role.BUILDER).role().canReview()).isFalse();
    }
}
