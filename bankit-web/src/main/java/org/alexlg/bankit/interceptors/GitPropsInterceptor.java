/*
 * Copyright (C) 2012 Alexandre Thomazo
 *
 * This file is part of BankIt.
 *
 * BankIt is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BankIt is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BankIt. If not, see <http://www.gnu.org/licenses/>.
 */
package org.alexlg.bankit.interceptors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.alexlg.bankit.controllers.GitProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ui.ModelMap;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

/**
 * Add some Git Properties into the model before rendering the page.
 * 
 * @author Alexandre Thomazo
 */
public class GitPropsInterceptor extends HandlerInterceptorAdapter {

	@Autowired
	private GitProperties gitProperties;
	
	@Override
	public void postHandle(HttpServletRequest request,
			HttpServletResponse response, Object handler,
			ModelAndView modelAndView) throws Exception {
		
		//don't add to model if redirect
		String view = modelAndView != null ? modelAndView.getViewName() : null;
		if (view == null || view.startsWith("redirect:/")) return;
		
		//check if model exists
		if (modelAndView != null && modelAndView.getModelMap() != null) {
			ModelMap model = modelAndView.getModelMap();
			model.addAttribute("gitDescribe", gitProperties.getDescribe());
		}
	}
}
