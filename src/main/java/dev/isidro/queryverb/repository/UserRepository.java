package dev.isidro.queryverb.repository;

import dev.isidro.queryverb.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
