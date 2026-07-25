package com.microservice.userservice.repository;

import com.microservice.userservice.model.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {

    Optional<Student> findByStudentCode(String studentCode);

    Optional<Student> findByEmail(String email);

    boolean existsByStudentCode(String studentCode);

    boolean existsByEmail(String email);
}
