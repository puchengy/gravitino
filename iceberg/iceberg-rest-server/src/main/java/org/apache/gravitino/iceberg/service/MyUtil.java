package org.apache.gravitino.iceberg.service;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;

public class MyUtil {

  @Context private HttpServletRequest request;

  /*
   * Get Pinterest user identity as a string from the request headers.
   */
  public String getKey() {
    return request.getHeader("abc");
  }
}