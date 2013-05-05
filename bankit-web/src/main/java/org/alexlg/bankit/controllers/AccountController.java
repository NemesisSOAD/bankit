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
package org.alexlg.bankit.controllers;

import org.alexlg.bankit.dao.CategoryDao;
import org.alexlg.bankit.dao.CostDao;
import org.alexlg.bankit.dao.OperationDao;
import org.alexlg.bankit.db.Category;
import org.alexlg.bankit.db.Cost;
import org.alexlg.bankit.db.Operation;
import org.alexlg.bankit.services.OptionsService;
import org.alexlg.bankit.services.SyncService;
import org.alexlg.bankit.validgroup.AddPlannedOp;
import org.joda.time.LocalDate;
import org.joda.time.YearMonth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.ModelMap;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.annotation.PostConstruct;
import javax.validation.Valid;
import javax.validation.groups.Default;
import java.beans.PropertyEditorSupport;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Controller which handles all operations for
 * displaying or editing account operations.
 * 
 * @author Alexandre Thomazo
 */
@Controller
@RequestMapping("/account")
public class AccountController {

	/** Number of future month to display */
	public static final int NB_FUTURE_MONTH = 1;
	
	@Autowired
	private OperationDao operationDao;
	
	@Autowired
	private CostDao costDao;
	
	@Autowired
	private CategoryDao categoryDao;
	
	@Autowired
	private SyncService syncService;
	
	@Autowired
	private OptionsService optionsService;
	
	@RequestMapping("/")
	public String index() {
		return "redirect:/account/list";
	}
	
	/**
	 * Initialize binder to handle specific type.
	 * @param binder Binder to initialize.
	 */
	@InitBinder
	public void binder(WebDataBinder binder) {
		final SimpleDateFormat dateFormatFull = new SimpleDateFormat("dd/MM/yyyy");
		final SimpleDateFormat dateFormatShort = new SimpleDateFormat("dd/MM");
		
		binder.registerCustomEditor(Date.class, new PropertyEditorSupport() {
			@Override
			public String getAsText() {
				if (getValue() == null) return "";
				return dateFormatFull.format((Date) getValue());
			}

			@Override
			public void setAsText(String text) throws IllegalArgumentException {
				//short format
				try {
					Date date = dateFormatShort.parse(text);
					//setting current year
					LocalDate lDate = new LocalDate(date).withYear(new LocalDate().getYear());
					setValue(lDate.toDate());
					return;
				} catch (ParseException e) {
					setValue(null);
				}
				//full format
				try {
					setValue(dateFormatFull.parse(text));
					return;
				} catch (ParseException e) {
					setValue(null);
				}
			}
		});
	}
	
	/**
	 * Display operations list for history and future.
	 * @param model Model to fill with operations list
	 * @return view name
	 */
	@RequestMapping("/list")
	@Transactional(readOnly=true)
	public String list(@RequestParam(required = false) String startDate,
					   @RequestParam(required = false) String endDate,
					   ModelMap model) {

		//start/end date of operations displayed
		LocalDate startDay = null;
		LocalDate endDay = null;
		boolean buildFuture = false;

		//parse dates if present
		if (startDate != null) startDay = parseMonth(startDate);
		if (endDate != null) endDay = parseMonth(endDate);

		//select start/end day from parsing or default
		if (startDay == null) startDay = calculateFirstHistoDay();
		if (endDay == null) {
			endDay = new LocalDate();
			buildFuture = true;
		} else {
			//select the last day of the endMonth
			endDay = endDay.dayOfMonth().withMaximumValue();

			//force endDay to not go beyond today
			LocalDate today = new LocalDate();
			if (endDay.isAfter(today)) {
				endDay = today;
				buildFuture = true;
			}
		}

		//switch start/end if not in correct order
		if (startDay.isAfter(endDay)) {
			LocalDate tmp = endDay;
			endDay = startDay;
			startDay = tmp;
		}

		//getting history operations
		List<Operation> ops = operationDao.getHistory(startDay, endDay);
		
		//calculating balance for history
		//current balance
		BigDecimal current = operationDao.getBalanceHistory(startDay);
		//difference between planned and real
		BigDecimal currentDiff = new BigDecimal("0");
		//balance for planned op but not debited
		BigDecimal plannedWaiting = new BigDecimal("0");
		
		//checking if a balance exists or init the account
		if (current == null && ops.size() == 0) {
			return "redirect:/account/init";
		}
		
		if (current == null) current = new BigDecimal("0");
		BigDecimal initialBalance = current;
		
		//calculating total for old operations
		for (Operation op : ops) {
			BigDecimal amount = op.getAmount();
			BigDecimal planned = op.getPlanned();
			
			if (amount != null) {
				//operation done
				current = current.add(amount);
				op.setTotal(current);
				if (planned != null) {
					currentDiff = currentDiff.add(amount).subtract(planned);
				}
			}
		}
		
		//calculating total for planned undebit operations
		for (Operation op : ops) {
			if (op.getAmount() == null) {
				plannedWaiting = plannedWaiting.add(op.getPlanned());
				op.setTotal(current.add(plannedWaiting));
			}
		}

		if (buildFuture) {
			//getting future operations
			Set<MonthOps> futureOps = buildFutureOps(endDay,
					operationDao.getFuture(endDay), costDao.getList(),
					current.add(plannedWaiting), NB_FUTURE_MONTH);

			model.put("futureOps", futureOps);
		}

		model.put("startDay", startDay.toDate());
		model.put("endDay", endDay.toDate());
		model.put("ops", ops);
		model.put("current", current);
		model.put("currentDiff", currentDiff);
		model.put("periodBalance", current.subtract(initialBalance));
		model.put("plannedWaiting", plannedWaiting);
		model.put("currentWaiting", current.add(plannedWaiting));
		model.put("lastSyncDate", optionsService.getDate(SyncService.OP_SYNC_OPT));
		model.put("categories", categoryDao.getList());
		//get categories summary (for previous and current month)
		model.put("categoriesSummary", buildCategories(startDay, endDay));
		
		return "account/list";
	}
	
	/**
	 * Show adding operation form.
	 * @param model Model to fill with current operation object
	 * @return View
	 */
	@RequestMapping(value="/add", method=RequestMethod.GET)
	public String showAddOperationForm(ModelMap model) {
		Operation op = new Operation();
		//setting default values
		op.setDebit(true);
		model.addAttribute("operation", op);
		return "account/add";
	}
	
	/**
	 * Add an future planned operation
	 * @param op Operation to add
	 * @param result Result of the binding between the POST and the object
	 * @param redirectAttributes Use to add some data into the model after the redirect
	 * @return View name
	 */
	@RequestMapping(value="/add", method=RequestMethod.POST)
	@Transactional
	public String addOperation(@ModelAttribute @Validated({ Default.class, AddPlannedOp.class }) Operation op, 
			BindingResult result,
			RedirectAttributes redirectAttributes) {
		
		if (op.getPlanned() == null) {
			result.rejectValue("planned", "javax.validation.constraints.NotNull.message");
		}
		
		if (result.hasErrors()) {
			return "account/add";
		} else {
			if (op.isDebit()) {
				//negate the planned amount
				op.setPlanned(op.getPlanned().negate());
				op.setDebit(false);
			}
			operationDao.insert(op);
			redirectAttributes.addFlashAttribute("added", op.getOperationId());
			return "redirect:/account/list";
		}
	}
	
	/**
	 * Delete an operation
	 * @param opId Id of the operation to delete
	 * @param redirectAttributes Use to add some data into the model after the redirect
	 * @return View name
	 */
	@RequestMapping("/del/{opId}")
	@Transactional
	public String delOperation(@PathVariable int opId, RedirectAttributes redirectAttributes) {
		Operation op = operationDao.get(opId);
		if (op != null) {
			operationDao.delete(op);
			redirectAttributes.addFlashAttribute("deleted", true);
		}
		return "redirect:/account/list";
	}
	
	/**
	 * "Unmerge" an operation by deleting the planned
	 * amount of the operation.
	 * @param opId Id of the operation which delete the planned amount
	 * @return View name
	 */
	@RequestMapping("/unmerge/{opId}")
	@Transactional
	public String unmerge(@PathVariable int opId) {
		Operation op = operationDao.get(opId);
		if (op != null) {
			op.setPlanned(null);
			operationDao.save(op);
		}
		return "redirect:/account/list";
	}
	
	/**
	 * Sync a file for the bank with the account.
	 * @param file File to sync.
	 * @return View name
	 * @throws IOException If an error occurs when reading the bank file
	 */
	@RequestMapping(value="/sync", method=RequestMethod.POST)
	@Transactional(rollbackFor=IOException.class)
	public String sync(@RequestParam("file") MultipartFile file) throws IOException {
		if (!file.isEmpty()) {
			syncService.readQifAndInsertOp(file.getInputStream());
			syncService.mergeOldPlannedOps();
		}
		
		return "redirect:/account/list";
	}
	
	/**
	 * Init accout by adding initial amount operation
	 * @param model Model to fill with current operation object
	 * @return View
	 */
	@RequestMapping(value="/init", method=RequestMethod.GET)
	public String initAccount(ModelMap model) {
		//creating the init operation, one month before
		Operation op = new Operation();
		LocalDate date = new LocalDate();
		op.setOperationDate(date.toDate());
		op.setLabel("Solde initial");
		
		model.addAttribute("operation", op);
		return "account/init";
	}
	
	/**
	 * Create the init operation in the account
	 * @param op Operation to add
	 * @param result Result of the binding between the POST and the object
	 * @return View name
	 */
	@RequestMapping(value="/init", method=RequestMethod.POST)
	@Transactional
	public String initAccount(@ModelAttribute @Valid Operation op, BindingResult result) {
		
		if (op.getAmount() == null) {
			result.rejectValue("amount", "javax.validation.constraints.NotNull.message");
		}
		
		if (result.hasErrors()) {
			return "account/init";
		} else {
			operationDao.insert(op);
			syncService.materializeCostsIntoOperation();
			optionsService.set(SyncService.OP_SYNC_OPT, op.getOperationDate());
			return "redirect:/account/list";
		}
	}
	
	
	/**
	 * Update the category of an operation
	 * @param opId Operation id to update
	 * @param catId Category to set
	 * @return Update operation result
	 */
	@RequestMapping(value="/update_cat.json", method=RequestMethod.POST, produces={"application/json"})
	@Transactional
	@ResponseBody
	public Map<String, Boolean> saveCategory(@RequestParam("op") int opId,
			@RequestParam("cat") int catId) throws Exception {
		
		Operation op = operationDao.get(opId);
		if (op == null) throw new Exception("Operation [" + opId + "] inexistante.");
		
		Category cat = null;
		if (catId != -1) {
			cat = categoryDao.get(catId);
			if (cat == null) throw new Exception("Catégory [" + catId + "] inexistante.");
		}
		
		op.setCategory(cat);
		operationDao.save(op);
		
		Map<String, Boolean> res = new HashMap<String, Boolean>(1);
		res.put("isOk", true);
		return res;
	}
	
	/**
	 * Handles exception which happens in controller
	 * @param e Exception raised
	 * @return View name
	 */
	@ExceptionHandler(Exception.class)
	public ModelAndView handleException(Exception e) {
		ModelAndView modelView = new ModelAndView("error");
		
		modelView.addObject("errorName", e.getMessage());
		
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		
		modelView.addObject("errorTrace", sw.toString());
		
		return modelView;
	}
	
	/**
	 * Run materializeCostsIntoOperation at the start of the application.
	 */
	@PostConstruct
	public void scheduleMaterializeCostsIntoOperation() {
		syncService.materializeCostsIntoOperation();
	}
	
	/**
	 * Build a set of MonthOps for all future ops : "manual" or costs (beyond 2 days of current)
	 * @param day Current day
	 * @param futurePlannedOps List of manual future operations
	 * @param costs List of costs
	 * @param balance Start balance
	 * @param nbMonth Number of month to build in addition to the current month.
	 * @return A set of MonthOps for each month planned
	 */
	protected Set<MonthOps> buildFutureOps(LocalDate day, List<Operation> futurePlannedOps,
			List<Cost> costs, BigDecimal balance, int nbMonth) {
		
		Set<MonthOps> futureOps = new TreeSet<MonthOps>();
		//going through all months
		for (int i = 0 ; i < nbMonth+1 ; i++) {
			LocalDate monthDate = day.monthOfYear().addToCopy(i);
			int lastDayOfMonth = monthDate.dayOfMonth().getMaximumValue();
			
			MonthOps monthOps = new MonthOps(monthDate, balance);
			futureOps.add(monthOps);
			
			//adding "manual" operation of the current month
			if (futurePlannedOps.size() > 0) {
				//loop an add all operation of the month
				for (Operation op : futurePlannedOps) {
					if (new LocalDate(op.getOperationDate()).getMonthOfYear() == monthDate.getMonthOfYear()) {
						op.setAuto(false);
						monthOps.addOp(op);
					}
				}
			}
			
			//adding costs of the current month
			LocalDate costStartDay = day.plusDays(2);
			for (Cost cost : costs) {
				int costDay = cost.getDay();
				
				//if the operation is planned after the last day of month
				//set it to the last day
				if (costDay > lastDayOfMonth) costDay = lastDayOfMonth;
				
				LocalDate opDate = new LocalDate(monthDate.getYear(), monthDate.getMonthOfYear(), costDay);
				//checking if we add the cost (the date is after current+2)
				if (opDate.isAfter(costStartDay)) {
					Operation op = new Operation();
					//setting a fake id for comparison (as we put the operation in the set)
					op.setOperationId(cost.getCostId() + i);
					op.setOperationDate(opDate.toDate());
					op.setPlanned(cost.getAmount());
					op.setLabel(cost.getLabel());
					op.setCategory(cost.getCategory());
					op.setAuto(true);
					monthOps.addOp(op);
				}
			}
			
			//saving current balance for next monthOp
			balance = monthOps.getBalance();
		}
		return futureOps;
	}
	
	/**
	 * Build categories summary for each month from startDate
	 * to previous nbPrevMonth
	 * @param startDate Start the summary for this month
	 * @param endDate Stop the summary for this month
	 * @return Map with the date of the month and a Map with Category
	 * 			and amount for this category for this month
	 */
	protected Map<Date, Map<Category, BigDecimal>> buildCategories(LocalDate startDate, LocalDate endDate) {
		Map<Date, Map<Category, BigDecimal>> categories = new LinkedHashMap<Date, Map<Category,BigDecimal>>();

		YearMonth curMonth = null; //month we start to retrieve
		YearMonth endMonth = null; //last month we have to retrieve
		if (startDate.isBefore(endDate)) {
			curMonth = new YearMonth(startDate.getYear(), startDate.getMonthOfYear());
			endMonth = new YearMonth(endDate.getYear(), endDate.getMonthOfYear());
		} else {
			curMonth = new YearMonth(endDate.getYear(), endDate.getMonthOfYear());
			endMonth = new YearMonth(startDate.getYear(), startDate.getMonthOfYear());
		}

		do {
			Map<Category, BigDecimal> monthSummary = categoryDao.getMonthSummary(curMonth);
			if (monthSummary.size() > 0) {
				categories.put(curMonth.toLocalDate(1).toDate(), monthSummary);
			}
			curMonth = curMonth.plusMonths(1);
		} while (curMonth.isBefore(endMonth) || curMonth.isEqual(endMonth));
		
		return categories;
	}

	/**
	 * Parse month pattern yyyy-mm to a LocalDate at the 1st of the month
	 * @param yearMonth Month pattern yyyy-mm
	 * @return LocalDate at the first of the month or null if can't be parsed
	 */
	protected LocalDate parseMonth(String yearMonth) {
		if (yearMonth.length() < 7) return null;

		String year = yearMonth.substring(0, 4);
		String month = yearMonth.substring(5, 7);

		try {
			int y = Integer.parseInt(year);
			int m = Integer.parseInt(month);
			return new LocalDate(y, m, 1);

		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * Calculate the first day of the history. If the current day is after the 7th day of the month,
	 * the return date will be the 1st of the current month. Otherwise, the return date
	 * will be the 1st of the previous month.
	 * @return First history day
	 */
	protected LocalDate calculateFirstHistoDay() {
		LocalDate date = new LocalDate();
		if (date.getDayOfMonth() > 7) {
			return date.withDayOfMonth(1);
		} else {
			return date.minusMonths(1).withDayOfMonth(1);
		}
	}
}
