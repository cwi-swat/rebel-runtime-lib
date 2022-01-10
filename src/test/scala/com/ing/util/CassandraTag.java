package com.ing.util;

import java.lang.annotation.*;

@org.scalatest.TagAnnotation
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface CassandraTag { }