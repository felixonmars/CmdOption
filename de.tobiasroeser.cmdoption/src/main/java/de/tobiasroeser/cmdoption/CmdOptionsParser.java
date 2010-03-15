package de.tobiasroeser.cmdoption;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class CmdOptionsParser {

	private Map<Class<? extends CmdOptionHandler>, CmdOptionHandler> handlerRegistry = new LinkedHashMap<Class<? extends CmdOptionHandler>, CmdOptionHandler>();

	private final boolean replaceCamelCaseByHyphen;

	private final Class<?> type;

	private final boolean handleHelp;

	public CmdOptionsParser(Class<?> type) {
		this(type, true, true);
	}

	public CmdOptionsParser(Class<?> type, boolean replaceCamelCaseByHyphen) {
		this(type, replaceCamelCaseByHyphen, true);
	}

	public CmdOptionsParser(Class<?> type, boolean replaceCamelCaseByHyphen,
			boolean handleHelp) {
		this.type = type;
		this.replaceCamelCaseByHyphen = replaceCamelCaseByHyphen;
		this.handleHelp = handleHelp;

		registerHandler(new BooleanOptionHandler());
		registerHandler(new BooleanFieldHandler());
		registerHandler(new StringFieldHandler());
		registerHandler(new PutIntoMapHandler());
		registerHandler(new AddToCollectionHandler());
		registerHandler(new StringMethodHandler());
	}

	public static final int EXIT_HELP = -1;
	public static final int EXIT_OK = 0;

	public static class Result {
		private final int code;
		private final String message;

		public Result(int code, String message) {
			this.code = code;
			this.message = message;
		}

		public Result(int code) {
			this(code, "");
		}

		public Result(String message) {
			this(1, message);
		}

		public int code() {
			return code;
		}

		public String message() {
			return message;
		}

		public boolean isHelp() {
			return code == EXIT_HELP;
		}

		public boolean isOk() {
			return code == EXIT_OK;
		}

	}

	public void unregisterAllHandler() {
		handlerRegistry.clear();
	}

	public void unregisterHandler(Class<? extends CmdOptionHandler> type) {
		if (type != null) {
			handlerRegistry.remove(type);
		}
	}

	public void registerHandler(CmdOptionHandler handler) {
		if (handler != null) {
			handlerRegistry.put(handler.getClass(), handler);
		}
	}

	public <T> Result parseCmdline(String[] cmdline, T config) {
		return parseCmdline(Arrays.asList(cmdline), config);
	}

	public <T> Result parseCmdline(List<String> cmdline, T config) {
		try {
			final List<Option> options = scanCmdOpions(config.getClass());
			int ok = internalCheckCmdline(options, cmdline);
			if (ok == EXIT_OK) {
				ok = internalParseCmdline(options, cmdline, config);
			}
			return new Result(ok);
		} catch (Exception e) {
			return new Result("Could not parse cmdline: " + e);
		}
	}

	private <T> int internalCheckCmdline(final List<Option> options,
			final List<String> cmdline) {

		if (handleHelp
				&& (cmdline.contains("--help") || cmdline.contains("-h"))) {
			return EXIT_HELP;
		}

		final Map<Option, Integer> foundOptions = new LinkedHashMap<Option, Integer>();
		for (Option option : options) {
			foundOptions.put(option, 0);
		}

		ArrayList<String> params = new ArrayList<String>(cmdline);
		paramsLoop: for (int index = 0; index < params.size(); ++index) {
			for (Option option : options) {
				if (option.match(params.get(index))) {
					if (params.size() <= index + option.getArgCount()) {
						int countOfGivenParams = params.size() - index - 1;
						throw new CmdOptionParseException("Missing arguments: "
								+ Arrays.asList(option.getArgs()).subList(
										countOfGivenParams,
										option.getArgCount()) + " Option "
								+ option + " requires " + option.getArgCount()
								+ " arguments, but you gave "
								+ countOfGivenParams + ".");
					}
					index = index + option.getArgCount();
					foundOptions.put(option, foundOptions.get(option) + 1);
					continue paramsLoop;
				}
			}
			// Unsupported option
			throw new CmdOptionParseException("Umsupported parameter: "
					+ params.get(index));
		}

		for (Entry<Option, Integer> e : foundOptions.entrySet()) {
			final Option option = e.getKey();
			final int count = e.getValue();
			if (count < option.getMinCount() || option.getMaxCount() != -1
					&& count > option.getMaxCount()) {
				throw new CmdOptionParseException("Cardinality of option "
						+ option
						+ " violated. Was given "
						+ count
						+ " times but supports "
						+ option.getMinCount()
						+ ".."
						+ (option.getMaxCount() == -1 ? "*" : option
								.getMaxCount()));
			}
		}

		return EXIT_OK;
	}

	private <T> int internalParseCmdline(final List<Option> options,
			final List<String> cmdline, final T config) {
		final ArrayList<String> params = cmdline instanceof ArrayList<?> ? (ArrayList<String>) cmdline
				: new ArrayList<String>(cmdline);

		paramsLoop: for (int index = 0; index < params.size(); ++index) {
			for (Option option : options) {
				if (option.match(params.get(index))) {
					String[] args = new String[option.getArgCount()];
					if (params.size() <= index + option.getArgCount()) {
						int countOfGivenParams = params.size() - index - 1;
						throw new CmdOptionParseException("Missing arguments: "
								+ Arrays.asList(option.getArgs()).subList(
										countOfGivenParams,
										option.getArgCount()) + " Option "
								+ option + " requires " + option.getArgCount()
								+ " arguments, but you gave "
								+ countOfGivenParams + ".");
					}
					for (int i = 0; i < option.getArgCount(); ++i) {
						++index;
						args[i] = params.get(index);
					}
					CmdOptionHandler handler = findHandler(option);
					if (handler == null) {
						throw new CmdOptionParseException(
								"Could not found any matching handler for element: "
										+ option.getElement());
					}

					AccessibleObject element = option.getElement();
					if (handler != null && element != null) {
						try {
							handler.applyParams(config, element, args);
						} catch (Exception e) {
							throw new CmdOptionParseException(
									"Could not apply parameters "
											+ Arrays.toString(args)
											+ " to field/method " + element, e);
						}
					} else {
						throw new CmdOptionParseException(
								"No handler registered to handle cmdline argument "
										+ element);
					}
					continue paramsLoop;
				}
			}
			// Unsupported option
			throw new CmdOptionParseException("Umsupported parameter: "
					+ params.get(index));
		}

		return EXIT_OK;
	}

	private CmdOptionHandler findHandler(Option option) {

		Class<? extends CmdOptionHandler> annoHandlerType = option
				.getCmdOptionHandler();

		CmdOptionHandler handler = null;
		if (!annoHandlerType.equals(CmdOptionHandler.class)) {
			if (handlerRegistry.containsKey(annoHandlerType)) {
				handler = handlerRegistry.get(annoHandlerType);
			} else {
				try {
					handler = annoHandlerType.newInstance();
				} catch (Exception e) {
					throw new CmdOptionParseException(
							"Could not create handler: " + annoHandlerType, e);
				}
				handlerRegistry.put(annoHandlerType, handler);
			}
		} else {
			// walk throw registered hander and find one
			// TODO: should we also walk throw self-added ones?
			for (CmdOptionHandler regHandle : handlerRegistry.values()) {
				if (regHandle.canHandle(option.getElement(), option
						.getArgCount())) {
					handler = regHandle;
					break;
				}
			}
		}

		return handler;
	}

	private String processOptionName(final String optionName) {
		if (replaceCamelCaseByHyphen) {
			StringBuilder name = new StringBuilder();
			for (char c : optionName.toCharArray()) {
				if (Character.isUpperCase(c)) {
					name.append("-").append(Character.toLowerCase(c));
				} else {
					name.append(c);
				}
			}
			return name.toString();
		} else {
			return optionName;
		}
	}

	public List<Option> scanCmdOpions(Class<?> configClass) {
		LinkedList<Option> options = new LinkedList<Option>();

		List<AccessibleObject> elements = new LinkedList<AccessibleObject>();
		elements.addAll(Arrays.asList(configClass.getFields()));
		elements.addAll(Arrays.asList(configClass.getMethods()));

		for (AccessibleObject element : elements) {
			CmdOption anno = element.getAnnotation(CmdOption.class);
			if (anno != null) {

				// Find a handler
				String longName = anno.longName().equals("") ? null : anno
						.longName();
				String shortName = anno.shortName().equals("") ? null : anno
						.shortName();

				if (longName == null && shortName == null) {
					if (element instanceof Field) {
						Field field = (Field) element;
						longName = processOptionName(field.getName());
					} else if (element instanceof Method) {
						Method method = (Method) element;
						longName = processOptionName(method.getName());
					} else {
						throw new CmdOptionParseException(
								"Could not determine option name");
					}
				}
				String description = anno.description();
				// if (!description.equals("") && anno.args().length > 0) {
				// description = MessageFormat.format(description,
				// (Object[]) anno.args());
				// }
				// TODO: replace other options in description string

				Class<? extends CmdOptionHandler> annoHandlerType = anno
						.handler();

				Option option = new Option(longName, shortName, description,
						annoHandlerType, element, anno.args(), anno.minCount(),
						anno.maxCount());
				options.add(option);
			}
		}

		return options;
	}

	public String formatOptions() {
		return formatOptions(null, true);
	}

	public String formatOptions(String prefix, boolean sorted) {
		LinkedList<String[]> optionsToFormat = new LinkedList<String[]>();

		List<Option> sortedOptions = new LinkedList<Option>(scanCmdOpions(type));
		if (sorted) {
			Collections.sort(sortedOptions);
		}

		for (Option option : sortedOptions) {
			optionsToFormat.add(new String[] { option.formatOptionString(),
					option.getDescription() });
		}

		int firstColSize = 8;
		for (String[] strings : optionsToFormat) {
			if (strings.length > 0) {
				firstColSize = Math.max(firstColSize, strings[0].length());
			}
		}
		firstColSize += 2;
		String optionsString = "";
		optionsString += prefix != null ? prefix : "Options:";
		for (String[] strings : optionsToFormat) {
			if (strings.length > 0) {
				optionsString += "\n" + strings[0];
			}
			if (strings.length > 1) {
				for (int count = firstColSize - strings[0].length(); count > 0; --count) {
					optionsString += " ";
				}
				optionsString += strings[1];
			}
		}

		return optionsString;
	}

}
